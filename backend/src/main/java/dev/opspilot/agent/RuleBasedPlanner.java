package dev.opspilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opspilot.agent.AgentReview.ToolCall;
import dev.opspilot.agent.RiskRules.Assessment;
import dev.opspilot.agent.RiskRules.Facts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic reviewer. Calls the same tools the LLM would, applies RiskRules,
 * and builds a rollout plan from templates. Always available, always explainable.
 */
@Component
public class RuleBasedPlanner {
    private final AgentTools tools;
    private final ObjectMapper json;

    public RuleBasedPlanner(AgentTools tools, ObjectMapper json) {
        this.tools = tools;
        this.json = json;
    }

    /** Gather the facts every review needs. Also used by the guardrail. */
    public Facts gatherFacts(ReviewInput in, List<ToolCall> trace) {
        var cluster = tools.getCluster(in.clusterName());
        trace.add(call("getCluster", "{\"clusterName\":\"" + in.clusterName() + "\"}", cluster));
        var capacity = tools.checkCapacity(in.clusterName(), in.type(), in.serverCount());
        trace.add(call("checkCapacity", "{\"type\":\"" + in.type() + "\",\"serverCount\":" + in.serverCount() + "}", capacity));
        var conflicts = tools.findConflicts(in.clusterName(), in.windowStart(), in.windowEnd(), in.requestId());
        trace.add(call("findConflicts", "{\"windowStart\":\"" + in.windowStart() + "\",\"windowEnd\":\"" + in.windowEnd() + "\"}", conflicts));
        var window = tools.checkChangeWindow(in.clusterName(), in.windowStart(), in.windowEnd());
        trace.add(call("checkChangeWindow", "{}", window));
        return new Facts(cluster, capacity, conflicts, window);
    }

    public AgentReview review(ReviewInput in, String source) {
        List<ToolCall> trace = new ArrayList<>();
        Facts facts = gatherFacts(in, trace);
        Assessment a = RiskRules.assess(facts);

        String query = switch (in.type()) {
            case ADD_SERVERS -> "scale out add servers capacity";
            case REMOVE_SERVERS -> "scale in remove decommission servers";
            case OS_UPGRADE -> "os upgrade patching end-of-life";
            case CONFIG_CHANGE -> "configuration change";
        } + (facts.cluster().environment().name().equals("PROD") ? " production window" : "");
        var hits = tools.searchRunbooks(query);
        trace.add(call("searchRunbooks", "{\"query\":\"" + query + "\"}", hits.stream().map(RunbookSearch.Hit::slug).toList()));

        return new AgentReview(a.risk(), a.recommendation(), summary(in, facts, a), plan(in, facts, a),
                hits.stream().map(RunbookSearch.Hit::slug).toList(), source, trace, false);
    }

    private static String summary(ReviewInput in, Facts f, Assessment a) {
        StringBuilder sb = new StringBuilder();
        sb.append(in.type()).append(" on ").append(f.cluster().name()).append(" (").append(f.cluster().environment())
          .append(", ").append(f.cluster().activeServers()).append("/").append(f.cluster().maxServers()).append(" servers). ");
        if (a.hasBlockers()) sb.append("Blocking issues: ").append(String.join("; ", a.blockers())).append(". ");
        if (!a.concerns().isEmpty()) sb.append("Notes: ").append(String.join("; ", a.concerns())).append(". ");
        if (!a.hasBlockers()) sb.append("No blocking issues found.");
        return sb.toString().strip();
    }

    private static List<String> plan(ReviewInput in, Facts f, Assessment a) {
        List<String> steps = new ArrayList<>();
        if (a.hasBlockers()) {
            steps.add("Do not proceed as submitted. Resolve: " + String.join("; ", a.blockers()) + ".");
            if (!f.capacity().fits() && in.type() == dev.opspilot.request.RequestType.ADD_SERVERS) {
                long free = Math.max(0, f.capacity().maxServers() - f.capacity().currentServers());
                steps.add("Split the change: add at most " + free + " server(s) here and request a new cluster for the rest.");
            }
            return steps;
        }
        switch (in.type()) {
            case ADD_SERVERS -> {
                steps.add("Provision " + in.serverCount() + " server(s) from the approved golden image.");
                steps.add("Apply standard tags (cost-center, tier) and owner " + f.cluster().ownerTeam() + ".");
                steps.add("Add to the load balancer in drain mode and run health checks for 15 minutes.");
                steps.add("Shift traffic gradually: 10%, 50%, 100%.");
                steps.add("Rollback: remove the new servers from the load balancer and decommission them.");
            }
            case REMOVE_SERVERS -> {
                steps.add("Confirm the remaining " + f.capacity().serversAfter() + " server(s) can absorb peak traffic plus 30% headroom.");
                steps.add("Drain connections from the " + in.serverCount() + " server(s) being removed.");
                steps.add("Remove them from the load balancer and wait one full traffic cycle.");
                steps.add("Decommission the servers.");
                steps.add("Rollback: re-enable the drained servers.");
            }
            case OS_UPGRADE -> {
                steps.add("Snapshot every server before starting.");
                steps.add("Upgrade one canary server and soak for 24 hours.");
                steps.add("Upgrade the rest in batches of at most 20% of the cluster: drain, upgrade, reboot, health check, re-enable.");
                steps.add("Rollback: restore affected servers from their pre-upgrade snapshots.");
            }
            case CONFIG_CHANGE -> {
                steps.add("Peer-review the configuration diff.");
                steps.add("Apply to one server and verify.");
                steps.add("Roll out in batches with the configuration management tool.");
                steps.add("Rollback: revert the commit and re-apply.");
            }
        }
        return steps;
    }

    private ToolCall call(String tool, String args, Object result) {
        try {
            String r = json.writeValueAsString(result);
            return new ToolCall(tool, args, r.length() > 600 ? r.substring(0, 600) + "..." : r);
        } catch (JsonProcessingException e) {
            return new ToolCall(tool, args, String.valueOf(result));
        }
    }
}
