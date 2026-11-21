package dev.opspilot.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** opspilot.llm.* : any OpenAI-compatible chat-completions endpoint (OpenAI, Gemini, Groq, Ollama...). */
@ConfigurationProperties(prefix = "opspilot.llm")
public record LlmProperties(boolean enabled, String baseUrl, String apiKey, String model, int maxSteps,
                            Duration connectTimeout, Duration readTimeout) {
    public LlmProperties {
        if (maxSteps <= 0) maxSteps = 8;
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(5);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(30);
    }
}
