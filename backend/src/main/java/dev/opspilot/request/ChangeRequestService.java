package dev.opspilot.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opspilot.agent.AgentReview;
import dev.opspilot.agent.AgentService;
import dev.opspilot.agent.ReviewInput;
import dev.opspilot.audit.AuditService;
import dev.opspilot.auth.CurrentUser;
import dev.opspilot.common.ConflictException;
import dev.opspilot.common.NotFoundException;
import dev.opspilot.common.OpsMetrics;
import dev.opspilot.inventory.Cluster;
import dev.opspilot.inventory.InventoryService;
import dev.opspilot.inventory.Server;
import dev.opspilot.inventory.ServerRepository;
import dev.opspilot.request.RequestDtos.*;
import dev.opspilot.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ChangeRequestService {
    private static final Logger log = LoggerFactory.getLogger(ChangeRequestService.class);
    private static final String GOLDEN_OS = "Ubuntu 24.04";
    private static final LocalDate GOLDEN_OS_EOL = LocalDate.of(2029, 4, 30);

    private final ChangeRequestRepository requests;
    private final AppUserRepository users;
    private final InventoryService inventory;
    private final ServerRepository servers;
    private final AuditService audit;
    private final AgentService agent;
    private final OpsMetrics metrics;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public ChangeRequestService(ChangeRequestRepository requests, AppUserRepository users, InventoryService inventory,
                                ServerRepository servers, AuditService audit, AgentService agent, OpsMetrics metrics,
                                TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.requests = requests;
        this.users = users;
        this.inventory = inventory;
        this.servers = servers;
        this.audit = audit;
        this.agent = agent;
        this.metrics = metrics;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public RequestView create(CurrentUser me, CreateRequest in) {
        Instant now = clock.instant();
        if (!in.windowStart().isAfter(now)) {
            throw new IllegalArgumentException("windowStart must be in the future");
        }
        Cluster cluster = inventory.findCluster(in.clusterId());
        var requester = users.getReferenceById(me.id());
        ChangeRequest cr = requests.save(new ChangeRequest(in.title().strip(), in.description().strip(), in.type(),
                cluster, in.serverCount(), in.windowStart(), in.windowEnd(), requester));
        audit.record(cr.getId(), me.username(), "CREATED", in.type() + " on " + cluster.getName());
        metrics.requestCreated(in.type().name());
        log.info("Change request created id={} type={} cluster={}", cr.getId(), in.type(), cluster.getName());
        return toView(cr);
    }

    // ------------------------------------------------------------------ submit -> agent review

    /**
     * Three steps, deliberately NOT one transaction:
     *  1. tx: DRAFT -> AGENT_REVIEW (commits fast)
     *  2. no tx: run the agent (may call an LLM for several seconds)
     *  3. tx: save the review, AGENT_REVIEW -> PENDING_APPROVAL
     * Holding a DB connection during a slow network call would exhaust the pool under load.
     */
    public RequestView submit(CurrentUser me, Long id) {
        ReviewInput input = tx.execute(status -> {
            ChangeRequest cr = load(id);
            if (!cr.getRequester().getId().equals(me.id())) {
                throw new org.springframework.security.access.AccessDeniedException("Only the requester can submit");
            }
            cr.transitionTo(RequestStatus.AGENT_REVIEW, clock.instant());
            audit.record(id, me.username(), "SUBMITTED", null);
            return ReviewInput.of(cr);
        });

        AgentReview review;
        try {
            review = agent.review(input);   // never throws for LLM problems: falls back internally
        } catch (RuntimeException e) {
            // Even the fallback failed (e.g. DB down). Return the request to DRAFT so the user can retry.
            log.error("Agent review failed completely for request {}", id, e);
            tx.executeWithoutResult(s -> {
                ChangeRequest cr = load(id);
                cr.transitionTo(RequestStatus.DRAFT, clock.instant());
                audit.record(id, "opspilot-agent", "AGENT_FAILED", "Returned to draft: " + e.getClass().getSimpleName());
            });
            throw new ConflictException("Automated review is unavailable right now. Your request is back in DRAFT; try again shortly.");
        }

        return tx.execute(status -> {
            ChangeRequest cr = load(id);
            cr.applyAgentReview(review.riskLevel(), review.summary(), toJson(review.plan()), toJson(review.citations()),
                    review.recommendation(), review.source(), toJson(review.trace()), review.guardrailApplied());
            cr.transitionTo(RequestStatus.PENDING_APPROVAL, clock.instant());
            audit.record(id, "opspilot-agent", "AGENT_REVIEWED",
                    "risk=" + review.riskLevel() + " recommendation=" + review.recommendation() + " source=" + review.source()
                            + (review.guardrailApplied() ? " (guardrail applied)" : ""));
            return toView(cr);
        });
    }

    // ------------------------------------------------------------------ human decision

    @Transactional
    @PreAuthorize("hasAnyRole('APPROVER','ADMIN')")
    public RequestView approve(CurrentUser me, Long id, String comment) {
        ChangeRequest cr = load(id);
        requireNotOwnRequest(me, cr);
        cr.transitionTo(RequestStatus.APPROVED, clock.instant());
        cr.recordDecision(users.getReferenceById(me.id()), blankToNull(comment));
        audit.record(id, me.username(), "APPROVED", blankToNull(comment));
        recordDecisionMetrics(cr, "approved", ChangeRequest.Recommendation.APPROVE);
        return toView(cr);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('APPROVER','ADMIN')")
    public RequestView reject(CurrentUser me, Long id, String comment) {
        ChangeRequest cr = load(id);
        requireNotOwnRequest(me, cr);
        cr.transitionTo(RequestStatus.REJECTED, clock.instant());
        cr.recordDecision(users.getReferenceById(me.id()), comment.strip());
        audit.record(id, me.username(), "REJECTED", comment.strip());
        recordDecisionMetrics(cr, "rejected", ChangeRequest.Recommendation.REJECT);
        return toView(cr);
    }

    // ------------------------------------------------------------------ execute

    /**
     * Simulates executing the change against inventory. Re-checks capacity at execution time,
     * because inventory may have changed since the agent reviewed the request.
     */
    @Transactional
    @PreAuthorize("hasAnyRole('APPROVER','ADMIN')")
    public RequestView execute(CurrentUser me, Long id) {
        ChangeRequest cr = load(id);
        if (cr.getStatus() != RequestStatus.APPROVED) {
            throw new ConflictException("Only APPROVED requests can be executed (current: " + cr.getStatus() + ")");
        }
        Cluster cluster = cr.getCluster();
        List<Server> active = servers.findByClusterIdAndStatusOrderByHostname(cluster.getId(), Server.Status.ACTIVE);
        String details = switch (cr.getType()) {
            case ADD_SERVERS -> addServers(cr, cluster, active);
            case REMOVE_SERVERS -> removeServers(cr, active);
            case OS_UPGRADE -> upgradeOs(active);
            case CONFIG_CHANGE -> "Configuration applied to " + active.size() + " servers";
        };
        cr.transitionTo(RequestStatus.EXECUTED, clock.instant());
        audit.record(id, me.username(), "EXECUTED", details);
        log.info("Executed request id={} : {}", id, details);
        return toView(cr);
    }

    private String addServers(ChangeRequest cr, Cluster cluster, List<Server> active) {
        if (active.size() + cr.getServerCount() > cluster.getMaxServers()) {
            throw new ConflictException("Cluster " + cluster.getName() + " no longer has capacity: "
                    + active.size() + " + " + cr.getServerCount() + " > " + cluster.getMaxServers());
        }
        Server template = active.isEmpty() ? null : active.get(0);
        List<String> created = new ArrayList<>();
        for (int i = 1; i <= cr.getServerCount(); i++) {
            String host = cluster.getName() + "-cr" + cr.getId() + "-" + i;
            servers.save(new Server(host, cluster,
                    template == null ? 8 : template.getCpuCores(),
                    template == null ? 32 : template.getMemoryGb(),
                    GOLDEN_OS, GOLDEN_OS_EOL,
                    cluster.getOwnerTeam(),
                    template == null ? "" : template.getTags()));
            created.add(host);
        }
        return "Provisioned " + created;
    }

    private String removeServers(ChangeRequest cr, List<Server> active) {
        if (cr.getServerCount() > active.size()) {
            throw new ConflictException("Cannot remove " + cr.getServerCount() + " servers; only " + active.size() + " active");
        }
        // Remove newest first (highest id).
        List<Server> victims = active.stream().sorted(Comparator.comparing(Server::getId).reversed())
                .limit(cr.getServerCount()).toList();
        victims.forEach(Server::decommission);
        return "Decommissioned " + victims.stream().map(Server::getHostname).toList();
    }

    private String upgradeOs(List<Server> active) {
        LocalDate soon = LocalDate.now(clock).plusYears(1);
        List<Server> outdated = active.stream()
                .filter(s -> s.getOsEolDate() != null && s.getOsEolDate().isBefore(soon)).toList();
        outdated.forEach(s -> s.upgradeOs(GOLDEN_OS, GOLDEN_OS_EOL));
        return "Upgraded " + outdated.size() + " servers to " + GOLDEN_OS;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageView<RequestView> list(RequestStatus status, boolean mine, CurrentUser me, int page, int size) {
        var result = requests.search(status, mine ? me.id() : null,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageView<>(result.map(this::toView).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(CurrentUser me, Long id) {
        ChangeRequest cr = load(id);
        return new RequestDetail(toView(cr), audit.forRequest(id), allowedActions(me, cr));
    }

    /**
     * The server tells the UI which buttons to show. Permission logic lives in ONE place (here),
     * and every action is still re-checked when it's called.
     */
    static List<String> allowedActions(CurrentUser me, ChangeRequest cr) {
        boolean own = cr.getRequester().getId().equals(me.id());
        List<String> actions = new ArrayList<>();
        switch (cr.getStatus()) {
            case DRAFT -> { if (own) actions.add("SUBMIT"); }
            case PENDING_APPROVAL -> { if (me.isApprover() && !own) { actions.add("APPROVE"); actions.add("REJECT"); } }
            case APPROVED -> { if (me.isApprover()) actions.add("EXECUTE"); }
            default -> { }
        }
        return actions;
    }

    // ------------------------------------------------------------------ helpers

    private ChangeRequest load(Long id) {
        return requests.findWithDetailsById(id).orElseThrow(() -> new NotFoundException("Change request " + id + " not found"));
    }

    /** Separation of duties: nobody approves their own change. */
    private static void requireNotOwnRequest(CurrentUser me, ChangeRequest cr) {
        if (cr.getRequester().getId().equals(me.id())) {
            throw new org.springframework.security.access.AccessDeniedException("You cannot decide on your own request");
        }
    }

    private void recordDecisionMetrics(ChangeRequest cr, String outcome, ChangeRequest.Recommendation matching) {
        metrics.decision(outcome);
        if (cr.getSubmittedAt() != null) {
            metrics.approvalLatency(Duration.between(cr.getSubmittedAt(), clock.instant()), outcome);
        }
        if (cr.getAgentRecommendation() != null) {
            metrics.humanAgreement(cr.getAgentRecommendation() == matching);
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> fromJson(String s, TypeReference<List<T>> type) {
        if (s == null) return List.of();
        try {
            return json.readValue(s, type);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    RequestView toView(ChangeRequest cr) {
        AgentView agentView = cr.getRiskLevel() == null ? null : new AgentView(
                cr.getRiskLevel().name(),
                cr.getAgentRecommendation() == null ? null : cr.getAgentRecommendation().name(),
                cr.getAgentSummary(),
                fromJson(cr.getAgentPlan(), new TypeReference<>() { }),
                fromJson(cr.getAgentCitations(), new TypeReference<>() { }),
                cr.getAgentSource(), cr.isAgentGuardrail(),
                fromJson(cr.getAgentTrace(), new TypeReference<List<AgentReview.ToolCall>>() { }));
        return new RequestView(cr.getId(), cr.getTitle(), cr.getDescription(), cr.getType(),
                cr.getCluster().getId(), cr.getCluster().getName(), cr.getCluster().getEnvironment().name(),
                cr.getServerCount(), cr.getWindowStart(), cr.getWindowEnd(), cr.getStatus(),
                cr.getRequester().getUsername(),
                cr.getApprover() == null ? null : cr.getApprover().getUsername(),
                cr.getDecisionComment(), agentView, cr.getCreatedAt(), cr.getSubmittedAt(), cr.getDecidedAt(),
                cr.getExecutedAt(), cr.getVersion());
    }
}
