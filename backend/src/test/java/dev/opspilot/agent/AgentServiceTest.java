package dev.opspilot.agent;

import dev.opspilot.agent.AgentTools.*;
import dev.opspilot.common.OpsMetrics;
import dev.opspilot.inventory.Environment;
import dev.opspilot.request.ChangeRequest.Recommendation;
import dev.opspilot.request.ChangeRequest.Risk;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {
    LlmAgent llmAgent;
    RuleBasedPlanner rules;
    SimpleMeterRegistry registry;
    final ReviewInput in = LlmAgentTest.INPUT;

    static LlmProperties props(boolean enabled) {
        return new LlmProperties(enabled, "http://x", "k", "m", 5, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    static AgentReview review(Risk risk, Recommendation rec, String source) {
        return new AgentReview(risk, rec, "summary", List.of("step"), List.of(), source, List.of(), false);
    }

    /** Facts with no blockers on a PROD cluster: deterministic floor is MEDIUM/APPROVE. */
    static RiskRules.Facts prodFacts(boolean fits) {
        return new RiskRules.Facts(
                new ClusterInfo("p", Environment.PROD, "us-west-2", "t", 12, 10, 83.3, 0),
                new CapacityCheck("p", 10, 2, 12, 12, fits, 100, 0, true, fits ? "OK" : "Exceeds"),
                List.of(),
                new WindowCheck("p", Environment.PROD, "UTC", "", "", "TUESDAY", true, true, "ok"));
    }

    @BeforeEach
    void setUp() {
        llmAgent = mock(LlmAgent.class);
        rules = mock(RuleBasedPlanner.class);
        registry = new SimpleMeterRegistry();
        when(rules.review(any(), anyString())).thenAnswer(inv -> review(Risk.HIGH, Recommendation.REJECT, inv.getArgument(1)));
    }

    AgentService service(boolean enabled) {
        return new AgentService(llmAgent, rules, props(enabled), new OpsMetrics(registry));
    }

    @Test
    void usesRulesWhenLlmDisabled() {
        assertThat(service(false).review(in).source()).isEqualTo("RULES");
        verifyNoInteractions(llmAgent);
    }

    @Test
    void fallsBackWhenLlmThrows() {
        when(llmAgent.review(any())).thenThrow(new RuntimeException("connection reset"));
        assertThat(service(true).review(in).source()).isEqualTo("FALLBACK");
        assertThat(registry.counter("opspilot.agent.fallback", "reason", "llm_error").count()).isEqualTo(1);
    }

    @Test
    void fallsBackImmediatelyWhenCircuitIsOpen() {
        when(llmAgent.review(any())).thenThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("llm")));
        assertThat(service(true).review(in).source()).isEqualTo("FALLBACK");
        assertThat(registry.counter("opspilot.agent.fallback", "reason", "circuit_open").count()).isEqualTo(1);
    }

    @Test
    void guardrailRaisesRiskWhenLlmIsTooOptimistic() {
        when(rules.gatherFacts(any(), anyList())).thenReturn(prodFacts(false));   // capacity exceeded
        when(llmAgent.review(any())).thenReturn(review(Risk.LOW, Recommendation.APPROVE, "LLM"));

        AgentReview r = service(true).review(in);

        assertThat(r.riskLevel()).isEqualTo(Risk.HIGH);
        assertThat(r.recommendation()).isEqualTo(Recommendation.REJECT);
        assertThat(r.guardrailApplied()).isTrue();
        assertThat(r.summary()).contains("Guardrail");
        assertThat(r.source()).isEqualTo("LLM");
    }

    @Test
    void guardrailNeverLowersRisk() {
        when(rules.gatherFacts(any(), anyList())).thenReturn(prodFacts(true));    // floor MEDIUM
        when(llmAgent.review(any())).thenReturn(review(Risk.HIGH, Recommendation.REJECT, "LLM"));

        AgentReview r = service(true).review(in);

        assertThat(r.riskLevel()).isEqualTo(Risk.HIGH);   // more cautious LLM answer is kept
        assertThat(r.guardrailApplied()).isFalse();
    }
}
