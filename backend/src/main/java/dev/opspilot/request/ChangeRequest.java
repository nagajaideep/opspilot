package dev.opspilot.request;

import dev.opspilot.common.ConflictException;
import dev.opspilot.inventory.Cluster;
import dev.opspilot.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "change_request")
public class ChangeRequest {
    public enum Risk { LOW, MEDIUM, HIGH }
    public enum Recommendation { APPROVE, REJECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private String title;
    @Column(nullable = false) private String description;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private RequestType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "cluster_id")
    private Cluster cluster;

    @Column(name = "server_count", nullable = false) private int serverCount;
    @Column(name = "window_start", nullable = false) private Instant windowStart;
    @Column(name = "window_end", nullable = false) private Instant windowEnd;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private RequestStatus status = RequestStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "requester_id")
    private AppUser requester;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "approver_id")
    private AppUser approver;

    @Column(name = "decision_comment") private String decisionComment;

    // ----- agent output -----
    @Enumerated(EnumType.STRING) @Column(name = "risk_level") private Risk riskLevel;
    @Column(name = "agent_summary") private String agentSummary;
    @Column(name = "agent_plan") private String agentPlan;
    @Column(name = "agent_citations") private String agentCitations;
    @Enumerated(EnumType.STRING) @Column(name = "agent_recommendation") private Recommendation agentRecommendation;
    @Column(name = "agent_source") private String agentSource;
    @Column(name = "agent_trace") private String agentTrace;
    @Column(name = "agent_guardrail", nullable = false) private boolean agentGuardrail;

    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "executed_at") private Instant executedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    /** Optimistic locking: Hibernate adds "WHERE version = ?" to updates; a stale write fails. */
    @Version private long version;

    protected ChangeRequest() { }

    public ChangeRequest(String title, String description, RequestType type, Cluster cluster, int serverCount,
                         Instant windowStart, Instant windowEnd, AppUser requester) {
        this.title = title;
        this.description = description;
        this.type = type;
        this.cluster = cluster;
        this.serverCount = serverCount;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.requester = requester;
    }

    /** The ONLY way status changes. Guards the state machine in one place. */
    public void transitionTo(RequestStatus target, Instant now) {
        if (!status.canMoveTo(target)) {
            throw new ConflictException("Cannot move request " + id + " from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = now;
        switch (target) {
            case AGENT_REVIEW -> this.submittedAt = now;
            case APPROVED, REJECTED -> this.decidedAt = now;
            case EXECUTED -> this.executedAt = now;
            default -> { }
        }
    }

    public void recordDecision(AppUser approver, String comment) {
        this.approver = approver;
        this.decisionComment = comment;
    }

    public void applyAgentReview(Risk risk, String summary, String planJson, String citationsJson,
                                 Recommendation recommendation, String source, String traceJson, boolean guardrail) {
        this.riskLevel = risk;
        this.agentSummary = summary;
        this.agentPlan = planJson;
        this.agentCitations = citationsJson;
        this.agentRecommendation = recommendation;
        this.agentSource = source;
        this.agentTrace = traceJson;
        this.agentGuardrail = guardrail;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public RequestType getType() { return type; }
    public Cluster getCluster() { return cluster; }
    public int getServerCount() { return serverCount; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public RequestStatus getStatus() { return status; }
    public AppUser getRequester() { return requester; }
    public AppUser getApprover() { return approver; }
    public String getDecisionComment() { return decisionComment; }
    public Risk getRiskLevel() { return riskLevel; }
    public String getAgentSummary() { return agentSummary; }
    public String getAgentPlan() { return agentPlan; }
    public String getAgentCitations() { return agentCitations; }
    public Recommendation getAgentRecommendation() { return agentRecommendation; }
    public String getAgentSource() { return agentSource; }
    public String getAgentTrace() { return agentTrace; }
    public boolean isAgentGuardrail() { return agentGuardrail; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getExecutedAt() { return executedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
