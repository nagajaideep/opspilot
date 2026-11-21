package dev.opspilot.agent;

import dev.opspilot.request.ChangeRequest;
import dev.opspilot.request.RequestType;

import java.time.Instant;

/** An immutable snapshot of the request, taken inside a transaction, safe to use outside one. */
public record ReviewInput(Long requestId, String title, String description, RequestType type, String clusterName,
                          int serverCount, Instant windowStart, Instant windowEnd) {
    public static ReviewInput of(ChangeRequest cr) {
        return new ReviewInput(cr.getId(), cr.getTitle(), cr.getDescription(), cr.getType(),
                cr.getCluster().getName(), cr.getServerCount(), cr.getWindowStart(), cr.getWindowEnd());
    }
}
