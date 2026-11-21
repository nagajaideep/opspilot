package dev.opspilot.policy;

import dev.opspilot.inventory.Cluster;
import dev.opspilot.inventory.ClusterRepository;
import dev.opspilot.inventory.Server;
import dev.opspilot.inventory.ServerRepository;
import dev.opspilot.policy.PolicyViolation.EntityType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nightly compliance scan against the tagging & ownership standard (runbook "tagging-standard").
 * Idempotent: new findings are inserted, findings that disappeared are marked resolved,
 * still-open findings are left alone.
 * Note: with several app instances, guard this with a distributed lock (e.g. ShedLock).
 */
@Service
public class PolicyScanService {
    private static final Logger log = LoggerFactory.getLogger(PolicyScanService.class);
    static final List<String> REQUIRED_TAGS = List.of("cost-center", "tier");

    public record ScanResult(int opened, int resolved, long open) { }

    private final ClusterRepository clusters;
    private final ServerRepository servers;
    private final PolicyViolationRepository violations;
    private final Clock clock;
    private final AtomicLong openGauge = new AtomicLong();

    public PolicyScanService(ClusterRepository clusters, ServerRepository servers,
                             PolicyViolationRepository violations, Clock clock, MeterRegistry registry) {
        this.clusters = clusters;
        this.servers = servers;
        this.violations = violations;
        this.clock = clock;
        Gauge.builder("opspilot.policy.violations.open", openGauge, AtomicLong::get)
                .description("Open policy violations after the last scan").register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initGauge() { openGauge.set(violations.countByResolvedAtIsNull()); }

    @Scheduled(cron = "${opspilot.policy.cron}", zone = "UTC")
    public void scheduledScan() {
        ScanResult r = scan();
        log.info("Nightly policy scan: opened={} resolved={} open={}", r.opened(), r.resolved(), r.open());
    }

    @Transactional
    public ScanResult scan() {
        Instant now = clock.instant();
        Map<String, PolicyViolation> found = detect(now).stream()
                .collect(Collectors.toMap(PolicyViolation::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, PolicyViolation> open = violations.findByResolvedAtIsNullOrderByRuleAscEntityNameAsc().stream()
                .collect(Collectors.toMap(PolicyViolation::key, Function.identity()));

        int resolved = 0;
        for (var e : open.entrySet()) {
            if (!found.containsKey(e.getKey())) { e.getValue().resolve(now); resolved++; }
        }
        int opened = 0;
        for (var e : found.entrySet()) {
            if (!open.containsKey(e.getKey())) { violations.save(e.getValue()); opened++; }
        }
        long openCount = open.size() - resolved + opened;
        openGauge.set(openCount);
        return new ScanResult(opened, resolved, openCount);
    }

    /** Pure detection logic: returns every violation that exists right now. */
    List<PolicyViolation> detect(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        List<PolicyViolation> out = new ArrayList<>();
        for (Cluster c : clusters.findAll()) {
            if (isBlank(c.getOwnerTeam())) {
                out.add(new PolicyViolation(EntityType.CLUSTER, c.getId(), c.getName(), "CLUSTER_NO_OWNER",
                        "Cluster has no owner team", now));
            }
        }
        for (Server s : servers.findAllActiveWithCluster()) {
            if (isBlank(s.getOwner())) {
                out.add(new PolicyViolation(EntityType.SERVER, s.getId(), s.getHostname(), "SERVER_NO_OWNER",
                        "Server has no owner", now));
            }
            Set<String> keys = Arrays.stream(s.getTags().split(","))
                    .map(t -> t.split(":", 2)[0].trim()).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
            List<String> missing = REQUIRED_TAGS.stream().filter(t -> !keys.contains(t)).toList();
            if (!missing.isEmpty()) {
                out.add(new PolicyViolation(EntityType.SERVER, s.getId(), s.getHostname(), "SERVER_MISSING_TAGS",
                        "Missing required tag(s): " + String.join(", ", missing), now));
            }
            if (s.getOsEolDate() != null && s.getOsEolDate().isBefore(today)) {
                out.add(new PolicyViolation(EntityType.SERVER, s.getId(), s.getHostname(), "SERVER_EOL_OS",
                        s.getOsName() + " reached end of life on " + s.getOsEolDate(), now));
            }
        }
        return out;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
