package dev.opspilot.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole app against a real Postgres (Testcontainers) and calls it over HTTP via MockMvc.
 * Spring caches the context between test classes with the same configuration, so it starts once.
 */
@SpringBootTest(properties = {"opspilot.demo-users.enabled=true", "opspilot.demo-users.password=test-pass"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public abstract class IntegrationTestBase {
    protected static final ZoneId LA = ZoneId.of("America/Los_Angeles");   // us-west-2 clusters
    protected static final ZoneId NY = ZoneId.of("America/New_York");      // us-east-1 clusters

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;

    /** Each test starts from the seeded inventory with no requests. */
    @BeforeEach
    void resetData() {
        jdbc.execute("DELETE FROM audit_event");
        jdbc.execute("DELETE FROM change_request");
        jdbc.execute("DELETE FROM policy_violation");
        jdbc.execute("DELETE FROM server WHERE hostname LIKE '%-cr%'");
        jdbc.execute("UPDATE server SET status = 'ACTIVE'");
    }

    protected String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"test-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    /** A compliant production window for us-west-2: Tuesday 02:00-04:00 Los Angeles time. */
    protected static Instant[] prodWindow(int weeksAhead) { return prodWindow(weeksAhead, LA); }

    /** Windows are checked in the cluster region's local time, so the zone matters. */
    protected static Instant[] prodWindow(int weeksAhead, ZoneId zone) {
        LocalDate tue = LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.TUESDAY)).plusWeeks(weeksAhead);
        ZonedDateTime start = tue.atTime(2, 0).atZone(zone);
        return new Instant[]{start.toInstant(), start.plusHours(2).toInstant()};
    }

    protected long clusterId(String name) {
        return jdbc.queryForObject("SELECT id FROM cluster WHERE name = ?", Long.class, name);
    }

    protected ResultActions createRequest(String token, String type, String cluster, int count, Instant[] window)
            throws Exception {
        String body = """
                {"title":"%s %d on %s","description":"integration test","type":"%s","clusterId":%d,
                 "serverCount":%d,"windowStart":"%s","windowEnd":"%s"}
                """.formatted(type, count, cluster, type, clusterId(cluster), count, window[0], window[1]);
        return mvc.perform(post("/api/requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected long createAndSubmit(String token, String type, String cluster, int count, Instant[] window) throws Exception {
        long id = read(createRequest(token, type, cluster, count, window).andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(post("/api/requests/" + id + "/submit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return id;
    }

    protected ResultActions action(String token, long id, String action, String body) throws Exception {
        var req = post("/api/requests/" + id + "/" + action).header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    protected JsonNode getJson(String token, String url) throws Exception {
        return read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    protected JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
