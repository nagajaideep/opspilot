package dev.opspilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a tiny local fake of the OpenAI-compatible API, so the real HTTP code path is exercised. */
class OpenAiCompatibleLlmClientTest {
    static final ObjectMapper JSON = new ObjectMapper();
    HttpServer server;
    final AtomicReference<String> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = """
                {"choices":[{"message":{"role":"assistant","content":"{\\"ok\\":true}"}}]}""".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    OpenAiCompatibleLlmClient client() {
        var props = new LlmProperties(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-test",
                "test-model", 5, Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new OpenAiCompatibleLlmClient(RestClient.builder(), props, JSON);
    }

    @Test
    void sendsModelToolsAndBearerKeyAndReturnsTheMessage() throws Exception {
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "user").put("content", "hi");
        ArrayNode tools = JSON.createArrayNode();
        tools.addObject().put("type", "function");

        var msg = client().chat(messages, tools);

        assertThat(msg.path("content").asText()).isEqualTo("{\"ok\":true}");
        var body = JSON.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        assertThat(body.path("temperature").asInt()).isZero();
        assertThat(body.path("tools").size()).isEqualTo(1);
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void serverErrorsSurfaceAsExceptions() {
        status.set(503);
        assertThatThrownBy(() -> client().chat(JSON.createArrayNode(), null))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
