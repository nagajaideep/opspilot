package dev.opspilot.auth;

import dev.opspilot.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureObservability   // tests disable metric exporters by default; we want /actuator/prometheus
class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void apiRequiresAToken() throws Exception {
        mvc.perform(get("/api/requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        String token = login("riya");
        // Swap the payload for one claiming ADMIN; the signature no longer matches.
        String[] parts = token.split("\\.");
        String fakePayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"riya\",\"uid\":1,\"roles\":[\"ADMIN\"],\"iss\":\"opspilot\",\"exp\":9999999999}".getBytes());
        mvc.perform(get("/api/requests").header("Authorization", "Bearer " + parts[0] + "." + fakePayload + "." + parts[2]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordGivesGenericMessage() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"riya\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"nope\"}"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password."));
    }

    @Test
    void meReturnsTheCaller() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + login("alex")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alex"))
                .andExpect(jsonPath("$.role").value("APPROVER"));
    }

    @Test
    void auditLogIsForApproversAndAdminsOnly() throws Exception {
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + login("riya"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + login("alex"))).andExpect(status().isOk());
    }

    @Test
    void policyScanIsAdminOnly() throws Exception {
        mvc.perform(post("/api/policy/scan").header("Authorization", "Bearer " + login("alex"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/policy/scan").header("Authorization", "Bearer " + login("ada"))).andExpect(status().isOk());
    }

    @Test
    void healthAndMetricsArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("opspilot_policy_violations_open")));
    }

    @Test
    void unknownEndpointIs404Not500() throws Exception {
        mvc.perform(get("/api/does-not-exist").header("Authorization", "Bearer " + login("riya")))
                .andExpect(status().isNotFound());
    }

    @Test
    void responsesCarryRequestIdAndSecurityHeaders() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "test-request-0001"))
                .andExpect(header().string("X-Request-Id", "test-request-0001"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
