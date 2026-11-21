package dev.opspilot.agent;

import dev.opspilot.agent.RiskRules.Assessment;
import dev.opspilot.common.OpsMetrics;
import dev.opspilot.request.ChangeRequest.Recommendation;
import dev.opspilot.request.ChangeRequest.Risk;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;

/**
 * Orchestrates a review:
 *   - LLM disabled           -> rule-based planner (source RULES)
 *   - LLM fails / bad output -> rule-based planner (source FALLBACK), metric + log
 *   - LLM succeeds           -> apply guardrail: deterministic floor on risk and recommendation
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final LlmAgent llmAgent;
    private final RuleBasedPlanner rules;
    private final LlmProperties props;
    private final OpsMetrics metrics;

    public AgentService(LlmAgent llmAgent, RuleBasedPlanner rules, LlmProperties props, OpsMetrics metrics) {
        this.llmAgent = llmAgent;
        this.rules = rules;
        this.props = props;
        this.metrics = metrics;
    }

    public AgentReview review(ReviewInput in) {
        long t0 = System.nanoTime();
        AgentReview result = props.enabled() ? reviewWithLlm(in) : rules.review(in, "RULES");
        metrics.agentRun(result.source(), result.riskLevel().name(), Duration.ofNanos(System.nanoTime() - t0));
        log.info("Agent review request={} source={} risk={} recommendation={} guardrail={}",
                in.requestId(), result.source(), result.riskLevel(), result.recommendation(), result.guardrailApplied());
        return result;
    }

    private AgentReview reviewWithLlm(ReviewInput in) {
        AgentReview llm;
        try {
            llm = llmAgent.review(in);
        } catch (CallNotPermittedException e) {
            return fallback(in, "circuit_open", e);
        } catch (LlmAgent.InvalidAgentOutput e) {
            return fallback(in, "invalid_output", e);
        } catch (RuntimeException e) {
            return fallback(in, "llm_error", e);
        }
        return applyGuardrail(in, llm);
    }

    private AgentReview fallback(ReviewInput in, String reason, Exception e) {
        log.warn("LLM review failed for request {} ({}): {}. Using rule-based planner.", in.requestId(), reason, e.toString());
        metrics.agentFallback(reason);
        return rules.review(in, "FALLBACK");
    }

    /** The LLM may raise risk above the deterministic floor, never lower it. */
    AgentReview applyGuardrail(ReviewInput in, AgentReview llm) {
        Assessment floor = RiskRules.assess(rules.gatherFacts(in, new ArrayList<>()));
        Risk risk = max(llm.riskLevel(), floor.risk());
        Recommendation rec = floor.hasBlockers() ? Recommendation.REJECT : llm.recommendation();
        if (risk == llm.riskLevel() && rec == llm.recommendation()) return llm;
        metrics.guardrailOverride();
        log.warn("Guardrail overrode LLM for request {}: {}/{} -> {}/{}", in.requestId(),
                llm.riskLevel(), llm.recommendation(), risk, rec);
        String note = "[Guardrail: deterministic checks raised this to " + risk + "/" + rec
                + (floor.hasBlockers() ? " because: " + String.join("; ", floor.blockers()) : "") + "]";
        return llm.withGuardrail(risk, rec, note);
    }

    private static Risk max(Risk a, Risk b) { return a.ordinal() >= b.ordinal() ? a : b; }
}
