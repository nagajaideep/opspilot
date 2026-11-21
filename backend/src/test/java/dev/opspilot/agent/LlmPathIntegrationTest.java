package dev.opspilot.agent;

import dev.opspilot.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full submit flow with the LLM branch enabled, using the scripted fake instead of a real provider.
 */
@TestPropertySource(properties = "opspilot.llm.enabled=true")
class LlmPathIntegrationTest extends IntegrationTestBase {

    @TestConfiguration
    static class FakeLlm {
        @Bean @Primary
        ScriptedLlmClient scriptedLlmClient() { return new ScriptedLlmClient(); }
    }

    @Autowired ScriptedLlmClient llm;

    @BeforeEach
    void clearScript() { llm.received.clear(); }

    @Test
    void llmReviewIsStoredWithTrace() throws Exception {
        llm.toolCall("a", "checkCapacity", "{\"clusterName\":\"search-prod\",\"type\":\"ADD_SERVERS\",\"serverCount\":2}")
           .toolCall("b", "searchRunbooks", "{\"query\":\"scale out servers\"}")
           .finalAnswer("""
               {"riskLevel":"MEDIUM","recommendation":"APPROVE","summary":"14/20 after change.",
                "plan":["Provision","Rollback: remove"],"citations":["scale-out-cluster"]}""");
        String riya = login("riya");
        long id = createAndSubmit(riya, "ADD_SERVERS", "search-prod", 2, prodWindow(1, NY));

        var agent = getJson(riya, "/api/requests/" + id).at("/request/agent");
        assertThat(agent.get("source").asText()).isEqualTo("LLM");
        assertThat(agent.get("guardrailApplied").asBoolean()).isFalse();
        assertThat(agent.get("trace").findValuesAsText("tool")).containsExactly("checkCapacity", "searchRunbooks");
    }

    @Test
    void guardrailOverridesAnOptimisticLlmEndToEnd() throws Exception {
        // edge-cache-prod is full (4/4); the "LLM" (perhaps prompt-injected) claims LOW/APPROVE.
        llm.finalAnswer("""
               {"riskLevel":"LOW","recommendation":"APPROVE","summary":"Looks fine.","plan":["Do it","Rollback"]}""");
        String riya = login("riya");
        long id = createAndSubmit(riya, "ADD_SERVERS", "edge-cache-prod", 1, prodWindow(1));

        var agent = getJson(riya, "/api/requests/" + id).at("/request/agent");
        assertThat(agent.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(agent.get("recommendation").asText()).isEqualTo("REJECT");
        assertThat(agent.get("guardrailApplied").asBoolean()).isTrue();
    }

    @Test
    void garbageFromTheLlmFallsBackToRules() throws Exception {
        llm.finalAnswer("Sure! Looks good to me :)");
        String riya = login("riya");
        long id = createAndSubmit(riya, "CONFIG_CHANGE", "analytics-stg", 0, prodWindow(1));
        assertThat(getJson(riya, "/api/requests/" + id).at("/request/agent/source").asText()).isEqualTo("FALLBACK");
    }
}
