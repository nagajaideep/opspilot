package dev.opspilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test double for the LLM: replays pre-written assistant messages in order.
 * Lets us test the agent loop deterministically, offline, for free.
 */
public class ScriptedLlmClient implements LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Deque<String> replies = new ArrayDeque<>();
    public final List<ArrayNode> received = new ArrayList<>();

    public ScriptedLlmClient reply(String assistantMessageJson) {
        replies.add(assistantMessageJson);
        return this;
    }

    /** Assistant turn that asks for one tool call. */
    public ScriptedLlmClient toolCall(String id, String tool, String argsJson) {
        String escaped = argsJson.replace("\"", "\\\"");
        return reply("""
                {"role":"assistant","content":null,"tool_calls":[
                  {"id":"%s","type":"function","function":{"name":"%s","arguments":"%s"}}]}
                """.formatted(id, tool, escaped));
    }

    /** Final assistant turn whose content is the review JSON. */
    public ScriptedLlmClient finalAnswer(String reviewJson) {
        try {
            return reply("{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(reviewJson) + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public JsonNode chat(ArrayNode messages, ArrayNode tools) {
        received.add(messages.deepCopy());
        if (replies.isEmpty()) throw new IllegalStateException("ScriptedLlmClient ran out of replies");
        try {
            return JSON.readTree(replies.poll());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
