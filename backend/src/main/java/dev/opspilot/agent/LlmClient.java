package dev.opspilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** One chat-completion round trip. Returns the assistant message (content and/or tool_calls). */
public interface LlmClient {
    JsonNode chat(ArrayNode messages, ArrayNode tools);
}
