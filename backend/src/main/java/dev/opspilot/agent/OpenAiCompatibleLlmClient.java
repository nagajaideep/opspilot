package dev.opspilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * POST {baseUrl}/chat/completions with tools. Resilience4j wraps each call:
 *   Retry (outer)  - up to 3 attempts with backoff on timeouts, 5xx and 429 (see application.yml)
 *   CircuitBreaker - after repeated failures, stop calling for 30s and fail fast
 * When either gives up, the exception propagates and AgentService falls back to the rule-based planner.
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {
    private final RestClient http;
    private final LlmProperties props;
    private final ObjectMapper json;

    public OpenAiCompatibleLlmClient(RestClient.Builder builder, LlmProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        var factory = new SimpleClientHttpRequestFactory();   // honors JVM proxy settings
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        this.http = builder
                .baseUrl(props.baseUrl() == null ? "http://localhost" : props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    @Retry(name = "llm")
    @CircuitBreaker(name = "llm")
    public JsonNode chat(ArrayNode messages, ArrayNode tools) {
        ObjectNode body = json.createObjectNode();
        body.put("model", props.model());
        body.put("temperature", 0);   // as deterministic as possible: same input -> same review
        body.set("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }
        JsonNode resp = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (props.apiKey() != null && !props.apiKey().isBlank()) h.setBearerAuth(props.apiKey());
                })
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        JsonNode message = resp == null ? null : resp.path("choices").path(0).path("message");
        if (message == null || message.isMissingNode()) {
            throw new LlmException("LLM response had no choices[0].message");
        }
        return message;
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
    }
}
