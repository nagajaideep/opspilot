package dev.opspilot.agent;

import dev.opspilot.common.NotFoundException;
import dev.opspilot.inventory.Cluster;
import dev.opspilot.inventory.ClusterRepository;
import dev.opspilot.inventory.Environment;
import dev.opspilot.inventory.Server;
import dev.opspilot.inventory.ServerRepository;
import dev.opspilot.request.ChangeRequestRepository;
import dev.opspilot.request.RequestStatus;
import dev.opspilot.request.RequestType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * The agent's tools. All are READ-ONLY: even a fully compromised model can't change anything through them.
 */
@Component
@Transactional(readOnly = true)
public class AgentTools {
    /** Maintenance windows are defined in the region's local time. */
    static final Map<String, ZoneId> REGION_ZONES = Map.of(
            "us-west-2", ZoneId.of("America/Los_Angeles"),
            "us-east-1", ZoneId.of("America/New_York"),
            "eu-west-1", ZoneId.of("Europe/Dublin"));
    static final double PROD_MAX_UTILIZATION = 85.0;
    static final double PROD_MAX_REMOVAL_PERCENT = 25.0;

    public record ClusterInfo(String name, Environment environment, String region, String ownerTeam,
                              int maxServers, long activeServers, double utilizationPercent, long serversOnEolOs) { }

    public record CapacityCheck(String cluster, long currentServers, int change, long serversAfter, int maxServers,
                                boolean fits, double utilizationAfterPercent, double removalPercent,
                                boolean withinProdHeadroom, String note) { }

    public record Conflict(Long requestId, String title, String status, Instant windowStart, Instant windowEnd,
                           String requester) { }

    public record WindowCheck(String cluster, Environment environment, String localZone, String localStart,
                              String localEnd, String localDayOfWeek, boolean required, boolean compliant, String note) { }

    private final ClusterRepository clusters;
    private final ServerRepository servers;
    private final ChangeRequestRepository requests;
    private final RunbookSearch runbooks;
    private final Clock clock;

    public AgentTools(ClusterRepository clusters, ServerRepository servers, ChangeRequestRepository requests,
                      RunbookSearch runbooks, Clock clock) {
        this.clusters = clusters;
        this.servers = servers;
        this.requests = requests;
        this.runbooks = runbooks;
        this.clock = clock;
    }

    public ClusterInfo getCluster(String name) {
        Cluster c = cluster(name);
        List<Server> active = servers.findByClusterIdAndStatusOrderByHostname(c.getId(), Server.Status.ACTIVE);
        LocalDate today = LocalDate.now(clock);
        long eol = active.stream().filter(s -> s.getOsEolDate() != null && s.getOsEolDate().isBefore(today)).count();
        return new ClusterInfo(c.getName(), c.getEnvironment(), c.getRegion(), c.getOwnerTeam(), c.getMaxServers(),
                active.size(), pct(active.size(), c.getMaxServers()), eol);
    }

    public CapacityCheck checkCapacity(String clusterName, RequestType type, int serverCount) {
        Cluster c = cluster(clusterName);
        long current = servers.countByClusterIdAndStatus(c.getId(), Server.Status.ACTIVE);
        int delta = type.capacityDelta(serverCount);
        long after = current + delta;
        boolean fits = after >= 0 && after <= c.getMaxServers();
        double utilAfter = pct(Math.max(after, 0), c.getMaxServers());
        double removal = delta < 0 && current > 0 ? pct(-delta, current) : 0.0;
        boolean prod = c.getEnvironment() == Environment.PROD;
        boolean headroom = !prod || (utilAfter <= PROD_MAX_UTILIZATION && removal <= PROD_MAX_REMOVAL_PERCENT);
        String note;
        if (after > c.getMaxServers()) note = "Exceeds max_servers by " + (after - c.getMaxServers());
        else if (after < 0) note = "Cannot remove more servers than are active";
        else if (prod && removal > PROD_MAX_REMOVAL_PERCENT) note = "Removes more than 25% of a production cluster";
        else if (prod && utilAfter > PROD_MAX_UTILIZATION) note = "Production utilization would exceed 85%";
        else note = "OK";
        return new CapacityCheck(c.getName(), current, delta, after, c.getMaxServers(), fits, utilAfter, removal,
                headroom, note);
    }

    /** Requests on the same cluster, awaiting or holding approval, whose windows overlap. */
    public List<Conflict> findConflicts(String clusterName, Instant start, Instant end, Long excludeRequestId) {
        Cluster c = cluster(clusterName);
        var statuses = EnumSet.of(RequestStatus.AGENT_REVIEW, RequestStatus.PENDING_APPROVAL, RequestStatus.APPROVED);
        return requests.findOverlapping(c.getId(), start, end, excludeRequestId == null ? -1L : excludeRequestId, statuses)
                .stream()
                .map(cr -> new Conflict(cr.getId(), cr.getTitle(), cr.getStatus().name(), cr.getWindowStart(),
                        cr.getWindowEnd(), cr.getRequester().getUsername()))
                .toList();
    }

    /** Production rule: Tuesday-Thursday, 02:00-06:00 region-local time. */
    public WindowCheck checkChangeWindow(String clusterName, Instant start, Instant end) {
        Cluster c = cluster(clusterName);
        ZoneId zone = REGION_ZONES.getOrDefault(c.getRegion(), ZoneOffset.UTC);
        ZonedDateTime ls = start.atZone(zone);
        ZonedDateTime le = end.atZone(zone);
        boolean prod = c.getEnvironment() == Environment.PROD;
        DayOfWeek dow = ls.getDayOfWeek();
        boolean dayOk = dow == DayOfWeek.TUESDAY || dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY;
        boolean timeOk = ls.toLocalDate().equals(le.toLocalDate())
                && !ls.toLocalTime().isBefore(LocalTime.of(2, 0))
                && !le.toLocalTime().isAfter(LocalTime.of(6, 0));
        boolean compliant = !prod || (dayOk && timeOk);
        String note = !prod ? "No window restriction outside production"
                : compliant ? "Inside the approved production window"
                : "Outside the production window (Tue-Thu 02:00-06:00 " + zone + ")";
        return new WindowCheck(c.getName(), c.getEnvironment(), zone.getId(), ls.toLocalDateTime().toString(),
                le.toLocalDateTime().toString(), dow.name(), prod, compliant, note);
    }

    public List<RunbookSearch.Hit> searchRunbooks(String query) {
        return runbooks.search(query, 3);
    }

    private Cluster cluster(String name) {
        return clusters.findByNameIgnoreCase(name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException("Unknown cluster: " + name));
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0.0 : Math.round(1000.0 * part / whole) / 10.0;
    }
}
