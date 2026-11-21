package dev.opspilot.agent;

import dev.opspilot.agent.AgentTools.CapacityCheck;
import dev.opspilot.agent.AgentTools.ClusterInfo;
import dev.opspilot.agent.AgentTools.Conflict;
import dev.opspilot.agent.AgentTools.WindowCheck;
import dev.opspilot.inventory.Environment;
import dev.opspilot.request.ChangeRequest.Recommendation;
import dev.opspilot.request.ChangeRequest.Risk;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic risk policy, written as a pure function: facts in, assessment out.
 * Used two ways: (1) as the whole review when no LLM is available, and
 * (2) as a guardrail FLOOR the LLM can raise but never lower.
 *
 *  HIGH + REJECT  : exceeds capacity, overlapping change, prod change outside window, >25% prod removal
 *  MEDIUM         : any other prod change, or prod utilization would pass 85%
 *  LOW            : everything else
 */
public final class RiskRules {
    private RiskRules() { }

    public record Facts(ClusterInfo cluster, CapacityCheck capacity, List<Conflict> conflicts, WindowCheck window) { }

    public record Assessment(Risk risk, Recommendation recommendation, List<String> blockers, List<String> concerns) {
        public boolean hasBlockers() { return !blockers.isEmpty(); }
    }

    public static Assessment assess(Facts f) {
        List<String> blockers = new ArrayList<>();
        List<String> concerns = new ArrayList<>();
        boolean prod = f.cluster().environment() == Environment.PROD;

        if (!f.capacity().fits()) {
            blockers.add("Capacity: " + f.capacity().note() + " (" + f.capacity().currentServers() + " active, max "
                    + f.capacity().maxServers() + ")");
        }
        if (prod && f.capacity().removalPercent() > AgentTools.PROD_MAX_REMOVAL_PERCENT) {
            blockers.add("Removes " + f.capacity().removalPercent() + "% of a production cluster (limit 25%)");
        }
        if (!f.conflicts().isEmpty()) {
            blockers.add("Overlaps with request(s) " + f.conflicts().stream().map(c -> "#" + c.requestId()).toList()
                    + " on the same cluster");
        }
        if (!f.window().compliant()) {
            blockers.add(f.window().note());
        }

        if (prod) concerns.add("Production cluster");
        if (prod && f.capacity().fits() && f.capacity().utilizationAfterPercent() > AgentTools.PROD_MAX_UTILIZATION) {
            concerns.add("Utilization after change " + f.capacity().utilizationAfterPercent() + "% (> 85% headroom target)");
        }
        if (f.cluster().ownerTeam() == null) concerns.add("Cluster has no owner team");

        Risk risk = !blockers.isEmpty() ? Risk.HIGH : prod ? Risk.MEDIUM : Risk.LOW;
        Recommendation rec = blockers.isEmpty() ? Recommendation.APPROVE : Recommendation.REJECT;
        return new Assessment(risk, rec, List.copyOf(blockers), List.copyOf(concerns));
    }
}
