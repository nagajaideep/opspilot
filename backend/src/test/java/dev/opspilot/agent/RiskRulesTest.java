package dev.opspilot.agent;

import dev.opspilot.agent.AgentTools.CapacityCheck;
import dev.opspilot.agent.AgentTools.ClusterInfo;
import dev.opspilot.agent.AgentTools.Conflict;
import dev.opspilot.agent.AgentTools.WindowCheck;
import dev.opspilot.agent.RiskRules.Facts;
import dev.opspilot.inventory.Environment;
import dev.opspilot.request.ChangeRequest.Recommendation;
import dev.opspilot.request.ChangeRequest.Risk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-function tests: no Spring, no DB, run in milliseconds. */
class RiskRulesTest {

    static ClusterInfo cluster(Environment env) {
        return new ClusterInfo("c1", env, "us-west-2", "team", 10, 5, 50.0, 0);
    }

    static CapacityCheck capacity(boolean fits, double utilAfter, double removalPct) {
        return new CapacityCheck("c1", 5, 1, 6, 10, fits, utilAfter, removalPct, true, "OK");
    }

    static WindowCheck window(boolean compliant) {
        return new WindowCheck("c1", Environment.PROD, "UTC", "", "", "TUESDAY", true, compliant, compliant ? "ok" : "outside");
    }

    @Test
    void devChangeWithNoIssuesIsLowAndApproved() {
        var a = RiskRules.assess(new Facts(cluster(Environment.DEV), capacity(true, 60, 0), List.of(), window(true)));
        assertThat(a.risk()).isEqualTo(Risk.LOW);
        assertThat(a.recommendation()).isEqualTo(Recommendation.APPROVE);
        assertThat(a.blockers()).isEmpty();
    }

    @Test
    void prodChangeIsAtLeastMedium() {
        var a = RiskRules.assess(new Facts(cluster(Environment.PROD), capacity(true, 60, 0), List.of(), window(true)));
        assertThat(a.risk()).isEqualTo(Risk.MEDIUM);
        assertThat(a.recommendation()).isEqualTo(Recommendation.APPROVE);
    }

    @Test
    void exceedingCapacityIsHighAndRejected() {
        var a = RiskRules.assess(new Facts(cluster(Environment.DEV), capacity(false, 120, 0), List.of(), window(true)));
        assertThat(a.risk()).isEqualTo(Risk.HIGH);
        assertThat(a.recommendation()).isEqualTo(Recommendation.REJECT);
        assertThat(a.blockers()).anyMatch(b -> b.startsWith("Capacity"));
    }

    @Test
    void overlappingChangeIsHigh() {
        var conflict = new Conflict(7L, "other", "APPROVED", Instant.now(), Instant.now(), "sam");
        var a = RiskRules.assess(new Facts(cluster(Environment.STAGING), capacity(true, 50, 0), List.of(conflict), window(true)));
        assertThat(a.risk()).isEqualTo(Risk.HIGH);
        assertThat(a.blockers()).anyMatch(b -> b.contains("#7"));
    }

    @Test
    void prodOutsideWindowIsHigh() {
        var a = RiskRules.assess(new Facts(cluster(Environment.PROD), capacity(true, 50, 0), List.of(), window(false)));
        assertThat(a.risk()).isEqualTo(Risk.HIGH);
    }

    @Test
    void removingMoreThanQuarterOfProdIsHigh() {
        var a = RiskRules.assess(new Facts(cluster(Environment.PROD), capacity(true, 30, 40.0), List.of(), window(true)));
        assertThat(a.risk()).isEqualTo(Risk.HIGH);
        assertThat(a.blockers()).anyMatch(b -> b.contains("25%"));
    }

    @Test
    void highProdUtilizationIsAConcernNotABlocker() {
        var a = RiskRules.assess(new Facts(cluster(Environment.PROD), capacity(true, 91.7, 0), List.of(), window(true)));
        assertThat(a.risk()).isEqualTo(Risk.MEDIUM);
        assertThat(a.concerns()).anyMatch(c -> c.contains("91.7%"));
    }
}
