package dev.opspilot.request;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opspilot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowIntegrationTest extends IntegrationTestBase {

    @Test
    void happyPath_createSubmitApproveExecute_updatesInventoryAndAudit() throws Exception {
        String riya = login("riya"), alex = login("alex");
        long id = createAndSubmit(riya, "ADD_SERVERS", "search-prod", 2, prodWindow(1, NY));

        JsonNode pending = getJson(riya, "/api/requests/" + id);
        assertThat(pending.at("/request/status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.at("/request/agent/riskLevel").asText()).isEqualTo("MEDIUM");   // prod, fits, in window
        assertThat(pending.at("/request/agent/source").asText()).isEqualTo("RULES");
        assertThat(pending.at("/request/agent/citations").toString()).contains("scale-out-cluster");
        assertThat(pending.at("/allowedActions").size()).isZero();     // requester can't decide

        assertThat(getJson(alex, "/api/requests/" + id).at("/allowedActions").toString()).contains("APPROVE", "REJECT");

        action(alex, id, "approve", "{\"comment\":\"go\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.approver").value("alex"));
        action(alex, id, "execute", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));

        assertThat(getJson(riya, "/api/inventory/clusters/" + clusterId("search-prod")).get("activeServers").asInt()).isEqualTo(14);
        JsonNode history = getJson(riya, "/api/requests/" + id).get("history");
        assertThat(history.findValuesAsText("action"))
                .containsExactly("CREATED", "SUBMITTED", "AGENT_REVIEWED", "APPROVED", "EXECUTED");
    }

    @Test
    void agentFlagsCapacityAndConflicts() throws Exception {
        String riya = login("riya"), sam = login("sam");
        Instant[] w = prodWindow(1);
        long tooBig = createAndSubmit(riya, "ADD_SERVERS", "edge-cache-prod", 1, w);   // cluster is 4/4
        JsonNode agent = getJson(riya, "/api/requests/" + tooBig).at("/request/agent");
        assertThat(agent.get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(agent.get("recommendation").asText()).isEqualTo("REJECT");
        assertThat(agent.get("summary").asText()).contains("Exceeds max_servers");
        assertThat(agent.get("plan").toString()).contains("This cluster is full (4/4)").doesNotContain("at most 0");

        long overlapping = createAndSubmit(sam, "CONFIG_CHANGE", "edge-cache-prod", 0, w);
        assertThat(getJson(sam, "/api/requests/" + overlapping).at("/request/agent/summary").asText())
                .contains("#" + tooBig);
    }

    @Test
    void separationOfDuties_approverCannotApproveOwnRequest() throws Exception {
        String alex = login("alex");
        long id = createAndSubmit(alex, "CONFIG_CHANGE", "analytics-stg", 0, prodWindow(2));
        action(alex, id, "approve", null).andExpect(status().isForbidden());
    }

    @Test
    void requesterCannotApprove() throws Exception {
        String riya = login("riya"), sam = login("sam");
        long id = createAndSubmit(riya, "CONFIG_CHANGE", "analytics-stg", 0, prodWindow(2));
        action(sam, id, "approve", null).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheRequesterCanSubmitTheirDraft() throws Exception {
        String riya = login("riya"), sam = login("sam");
        long id = read(createRequest(riya, "OS_UPGRADE", "search-prod", 0, prodWindow(1))).get("id").asLong();
        action(sam, id, "submit", null).andExpect(status().isForbidden());
    }

    @Test
    void illegalTransitionsReturn409() throws Exception {
        String riya = login("riya"), alex = login("alex");
        long id = read(createRequest(riya, "OS_UPGRADE", "search-prod", 0, prodWindow(1))).get("id").asLong();
        action(alex, id, "approve", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot move request " + id + " from DRAFT to APPROVED"));
        action(alex, id, "execute", null).andExpect(status().isConflict());
    }

    @Test
    void rejectRequiresAReason() throws Exception {
        String riya = login("riya"), alex = login("alex");
        long id = createAndSubmit(riya, "CONFIG_CHANGE", "analytics-stg", 0, prodWindow(2));
        action(alex, id, "reject", "{\"comment\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.comment").exists());
        action(alex, id, "reject", "{\"comment\":\"Not this week\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void executionRechecksCapacity() throws Exception {
        String riya = login("riya"), sam = login("sam"), alex = login("alex");
        // payments-prod is 10/12. Two separately approved +2 requests can't both fit.
        long first = createAndSubmit(riya, "ADD_SERVERS", "payments-prod", 2, prodWindow(1));
        long second = createAndSubmit(sam, "ADD_SERVERS", "payments-prod", 2, prodWindow(2));
        action(alex, first, "approve", null).andExpect(status().isOk());
        action(alex, second, "approve", null).andExpect(status().isOk());
        action(alex, first, "execute", null).andExpect(status().isOk());
        action(alex, second, "execute", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no longer has capacity")));
    }

    @Test
    void validationErrorsListEveryField() throws Exception {
        String riya = login("riya");
        Instant[] w = prodWindow(1);
        createRequest(riya, "ADD_SERVERS", "search-prod", 0, new Instant[]{w[1], w[0]})
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.windowValid").exists())
                .andExpect(jsonPath("$.errors.serverCountValid").exists());
    }

    @Test
    void windowInThePastIsRejected() throws Exception {
        String riya = login("riya");
        Instant past = Instant.now().minusSeconds(7200);
        createRequest(riya, "CONFIG_CHANGE", "search-prod", 0, new Instant[]{past, past.plusSeconds(3600)})
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("windowStart must be in the future"));
    }
}
