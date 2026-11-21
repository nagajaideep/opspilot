package dev.opspilot.agent;

import dev.opspilot.request.ChangeRequest.Recommendation;
import dev.opspilot.request.ChangeRequest.Risk;

import java.util.List;

/**
 * The agent's output. source is LLM, FALLBACK (LLM failed, rules used) or RULES (no LLM configured).
 */
public record AgentReview(Risk riskLevel, Recommendation recommendation, String summary, List<String> plan,
                          List<String> citations, String source, List<ToolCall> trace, boolean guardrailApplied) {

    /** One tool invocation, kept so approvers can see exactly what the agent looked at. */
    public record ToolCall(String tool, String arguments, String result) { }

    public AgentReview withGuardrail(Risk risk, Recommendation rec, String note) {
        return new AgentReview(risk, rec, summary + " " + note, plan, citations, source, trace, true);
    }
}
