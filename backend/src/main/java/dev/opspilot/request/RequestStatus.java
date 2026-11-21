package dev.opspilot.request;

import java.util.EnumSet;
import java.util.Set;

/**
 * The change-request state machine. Each state lists the states it may move to.
 * Anything not listed is an illegal transition and is rejected with HTTP 409.
 *
 *   DRAFT -> AGENT_REVIEW -> PENDING_APPROVAL -> APPROVED -> EXECUTED
 *                                          \-> REJECTED
 */
public enum RequestStatus {
    DRAFT, AGENT_REVIEW, PENDING_APPROVAL, APPROVED, REJECTED, EXECUTED;

    public Set<RequestStatus> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(AGENT_REVIEW);
            case AGENT_REVIEW -> EnumSet.of(PENDING_APPROVAL, DRAFT);   // DRAFT = agent crashed hard, let user retry
            case PENDING_APPROVAL -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(EXECUTED);
            case REJECTED, EXECUTED -> EnumSet.noneOf(RequestStatus.class);   // terminal
        };
    }

    public boolean canMoveTo(RequestStatus target) { return next().contains(target); }
}
