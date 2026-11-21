package dev.opspilot.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.opspilot.agent.AgentReview;
import dev.opspilot.audit.AuditService.AuditView;
import jakarta.validation.constraints.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class RequestDtos {
    private RequestDtos() { }

    /** Input validation happens here, before any business logic runs. */
    public record CreateRequest(
            @NotBlank @Size(max = 140) String title,
            @NotBlank @Size(max = 4000) String description,
            @NotNull RequestType type,
            @NotNull @Positive Long clusterId,
            @Min(0) @Max(50) int serverCount,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd) {

        @JsonIgnore
        @AssertTrue(message = "windowEnd must be after windowStart and the window at most 8 hours")
        public boolean isWindowValid() {
            if (windowStart == null || windowEnd == null) return true;   // @NotNull reports these
            Duration d = Duration.between(windowStart, windowEnd);
            return !d.isNegative() && !d.isZero() && d.compareTo(Duration.ofHours(8)) <= 0;
        }

        @JsonIgnore
        @AssertTrue(message = "serverCount must be 1-50 for ADD_SERVERS/REMOVE_SERVERS and 0 otherwise")
        public boolean isServerCountValid() {
            if (type == null) return true;
            return type.needsServerCount() ? serverCount >= 1 : serverCount == 0;
        }
    }

    public record DecisionRequest(@Size(max = 1000) String comment) { }

    public record RejectRequest(@NotBlank(message = "A reason is required when rejecting") @Size(max = 1000) String comment) { }

    public record AgentView(String riskLevel, String recommendation, String summary, List<String> plan,
                            List<String> citations, String source, boolean guardrailApplied,
                            List<AgentReview.ToolCall> trace) { }

    public record RequestView(Long id, String title, String description, RequestType type,
                              Long clusterId, String clusterName, String environment, int serverCount,
                              Instant windowStart, Instant windowEnd, RequestStatus status,
                              String requester, String approver, String decisionComment,
                              AgentView agent, Instant createdAt, Instant submittedAt, Instant decidedAt,
                              Instant executedAt, long version) { }

    public record RequestDetail(RequestView request, List<AuditView> history, List<String> allowedActions) { }

    public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) { }
}
