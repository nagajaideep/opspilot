package dev.opspilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opspilot.request.RequestType;
import dev.opspilot.support.IntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline evaluation of the agent on eval/cases.json (30 labelled requests).
 * Scores three reviewers on the same cases:
 *   - RULES : deterministic planner (consistency check of the labels)
 *   - LLM raw : the model's own answer, before the guardrail  <- the honest "agent accuracy" number
 *   - LLM final : after the guardrail
 * Writes eval/report.md.  Run:  LLM_ENABLED=true LLM_API_KEY=... mvn test -Peval
 */
@Tag("eval")
class AgentEvaluationTest extends IntegrationTestBase {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Autowired RuleBasedPlanner rules;
    @Autowired LlmAgent llmAgent;
    @Autowired AgentService agentService;
    @Autowired LlmProperties llmProps;

    record Outcome(String risk, String rec, String error) {
        boolean matches(JsonNode exp) {
            return error == null && risk.equals(exp.get("riskLevel").asText()) && rec.equals(exp.get("recommendation").asText());
        }
        String show() { return error != null ? "error: " + error : risk + " / " + rec; }
    }

    @Test
    void evaluate() throws Exception {
        JsonNode cases = json.readTree(ROOT.resolve("eval/cases.json").toFile()).get("cases");
        boolean llm = llmProps.enabled();
        int rulesOk = 0, rawOk = 0, finalOk = 0, injectionOk = 0, injections = 0, guardrails = 0;
        List<String> rows = new ArrayList<>();

        for (JsonNode c : cases) {
            jdbc.execute("DELETE FROM audit_event");
            jdbc.execute("DELETE FROM change_request");
            ReviewInput in = setUp(c);
            JsonNode exp = c.get("expected");

            AgentReview r = rules.review(in, "RULES");
            Outcome ruleOut = new Outcome(r.riskLevel().name(), r.recommendation().name(), null);
            if (ruleOut.matches(exp)) rulesOk++;

            Outcome raw = null, fin = null;
            if (llm) {
                try {
                    AgentReview lr = llmAgent.review(in);
                    raw = new Outcome(lr.riskLevel().name(), lr.recommendation().name(), null);
                    AgentReview g = agentService.applyGuardrail(in, lr);
                    fin = new Outcome(g.riskLevel().name(), g.recommendation().name(), null);
                    if (g.guardrailApplied()) guardrails++;
                } catch (Exception e) {
                    raw = new Outcome(null, null, e.getClass().getSimpleName());
                    fin = ruleOut;   // production behavior: fall back to rules
                }
                if (raw.matches(exp)) rawOk++;
                if (fin.matches(exp)) finalOk++;
                if (c.get("title").asText().contains("injection")) {
                    injections++;
                    if (raw.matches(exp)) injectionOk++;
                }
            }
            rows.add("| %d | %s | %s / %s | %s | %s | %s |".formatted(c.get("id").asInt(), c.get("title").asText(),
                    exp.get("riskLevel").asText(), exp.get("recommendation").asText(),
                    mark(ruleOut, exp), raw == null ? "-" : mark(raw, exp), fin == null ? "-" : mark(fin, exp)));
        }

        int n = cases.size();
        StringBuilder md = new StringBuilder("# Agent evaluation report\n\n");
        md.append("Generated: ").append(Instant.now()).append("  \n");
        md.append("Cases: ").append(n).append(" (`eval/cases.json`)  \n");
        md.append("LLM: ").append(llm ? llmProps.model() + " via " + llmProps.baseUrl() : "not configured (set LLM_ENABLED, LLM_API_KEY)").append("\n\n");
        md.append("| Reviewer | Correct (risk AND recommendation) |\n|---|---|\n");
        md.append("| Rule-based planner (label consistency check) | ").append(pct(rulesOk, n)).append(" |\n");
        if (llm) {
            md.append("| **LLM agent, raw** | **").append(pct(rawOk, n)).append("** |\n");
            md.append("| LLM agent + guardrail (what users see) | ").append(pct(finalOk, n)).append(" |\n");
            md.append("\nPrompt-injection cases resisted by the raw LLM: ").append(injectionOk).append("/").append(injections)
              .append(". Guardrail overrides: ").append(guardrails).append(".\n");
        }
        md.append("\n| # | Case | Expected | Rules | LLM raw | LLM final |\n|---|---|---|---|---|---|\n");
        rows.forEach(r -> md.append(r).append('\n'));
        Files.writeString(ROOT.resolve("eval/report.md"), md.toString());
        System.out.println(md);
    }

    private ReviewInput setUp(JsonNode c) {
        String cluster = c.get("cluster").asText();
        String region = jdbc.queryForObject("SELECT region FROM cluster WHERE name = ?", String.class, cluster);
        ZoneId zone = AgentTools.REGION_ZONES.getOrDefault(region, ZoneOffset.UTC);
        LocalDate base = LocalDate.now(zone).plusWeeks(1);
        ZonedDateTime start = "in".equals(c.get("window").asText())
                ? base.with(TemporalAdjusters.next(DayOfWeek.TUESDAY)).atTime(2, 0).atZone(zone)
                : base.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).atTime(14, 0).atZone(zone);
        Instant ws = start.toInstant(), we = start.plusHours(2).toInstant();
        long clusterId = clusterId(cluster);
        long requester = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'riya'", Long.class);

        String insert = """
                INSERT INTO change_request (title, description, type, cluster_id, server_count, window_start, window_end, status, requester_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""";
        if (c.get("conflict").asBoolean()) {
            jdbc.queryForObject(insert, Long.class, "Other team's change", "existing", "CONFIG_CHANGE", clusterId, 0,
                    java.sql.Timestamp.from(ws), java.sql.Timestamp.from(we), "PENDING_APPROVAL", requester);
        }
        long id = jdbc.queryForObject(insert, Long.class, c.get("title").asText(), c.get("description").asText(),
                c.get("type").asText(), clusterId, c.get("serverCount").asInt(),
                java.sql.Timestamp.from(ws), java.sql.Timestamp.from(we), "AGENT_REVIEW", requester);
        return new ReviewInput(id, c.get("title").asText(), c.get("description").asText(),
                RequestType.valueOf(c.get("type").asText()), cluster, c.get("serverCount").asInt(), ws, we);
    }

    private static String mark(Outcome o, JsonNode exp) { return (o.matches(exp) ? "✅ " : "❌ ") + o.show(); }

    private static String pct(int ok, int n) { return "%d/%d (%.0f%%)".formatted(ok, n, 100.0 * ok / n); }
}
