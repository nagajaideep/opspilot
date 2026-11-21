package dev.opspilot.policy;

import dev.opspilot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyScanIntegrationTest extends IntegrationTestBase {
    @Autowired PolicyScanService scanner;

    @Test
    void scanFindsSeededViolationsAndIsIdempotent() {
        var first = scanner.scan();
        assertThat(first.opened()).isEqualTo(9);
        var second = scanner.scan();
        assertThat(second.opened()).isZero();
        assertThat(second.open()).isEqualTo(9);
    }

    @Test
    void fixedIssuesAreResolvedOnNextScan() {
        scanner.scan();
        jdbc.update("UPDATE cluster SET owner_team = 'ml-platform' WHERE name = 'ml-train-dev'");
        var r = scanner.scan();
        assertThat(r.resolved()).isEqualTo(1);
        assertThat(r.open()).isEqualTo(8);
        jdbc.update("UPDATE cluster SET owner_team = NULL WHERE name = 'ml-train-dev'");   // restore seed
    }
}
