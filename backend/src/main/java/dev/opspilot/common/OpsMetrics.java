package dev.opspilot.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Business metrics, exposed at /actuator/prometheus and charted in Grafana.
 * Naming: Micrometer "opspilot.requests.opened" becomes Prometheus "opspilot_requests_opened_total".
 * (Avoid a "created" suffix: Prometheus reserves "_created", so Micrometer strips it.)
 */
@Component
public class OpsMetrics {
    private final MeterRegistry registry;

    public OpsMetrics(MeterRegistry registry) { this.registry = registry; }

    public void requestCreated(String type) {
        Counter.builder("opspilot.requests.opened").tag("type", type)
                .description("Change requests created").register(registry).increment();
    }

    public void decision(String outcome) {
        Counter.builder("opspilot.requests.decided").tag("outcome", outcome)
                .description("Approve/reject decisions").register(registry).increment();
    }

    /** Time from submit to human decision: the key "how fast do we approve" number. */
    public void approvalLatency(Duration d, String outcome) {
        Timer.builder("opspilot.approval.latency").tag("outcome", outcome)
                .description("Submit-to-decision time").publishPercentileHistogram()
                .register(registry).record(d);
    }

    /** Agent runs by source (LLM / FALLBACK / RULES) and resulting risk. */
    public void agentRun(String source, String risk, Duration d) {
        Timer.builder("opspilot.agent.run").tag("source", source).tag("risk", risk)
                .description("Agent review duration").publishPercentileHistogram()
                .register(registry).record(d);
    }

    public void agentFallback(String reason) {
        Counter.builder("opspilot.agent.fallback").tag("reason", reason)
                .description("LLM failures that fell back to the rule-based planner").register(registry).increment();
    }

    public void guardrailOverride() {
        Counter.builder("opspilot.agent.guardrail").description("Times deterministic checks overrode the LLM")
                .register(registry).increment();
    }

    /** Did the human agree with the agent's recommendation? This is the online "agent accuracy" signal. */
    public void humanAgreement(boolean agreed) {
        Counter.builder("opspilot.agent.human_agreement").tag("agreed", String.valueOf(agreed))
                .description("Human decision matched agent recommendation").register(registry).increment();
    }
}
