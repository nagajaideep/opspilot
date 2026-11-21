package dev.opspilot.request;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RequestStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "DRAFT, AGENT_REVIEW, true",
            "DRAFT, APPROVED, false",
            "AGENT_REVIEW, PENDING_APPROVAL, true",
            "AGENT_REVIEW, DRAFT, true",
            "PENDING_APPROVAL, APPROVED, true",
            "PENDING_APPROVAL, REJECTED, true",
            "PENDING_APPROVAL, EXECUTED, false",
            "APPROVED, EXECUTED, true",
            "REJECTED, APPROVED, false",
            "EXECUTED, DRAFT, false"
    })
    void transitions(RequestStatus from, RequestStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }
}
