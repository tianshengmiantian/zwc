package com.westart.ai.westart.ai.config;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Creates LangChain4j chat models against Bailian's OpenAI-compatible API.
 * Keeping client construction here prevents provider details from leaking into
 * business services and gives the team one place to change timeouts or vendors.
 */
@Component
public final class LangChain4jModelFactory {

    private static final String UNCONFIGURED_KEY = "not-configured";

    private final String apiKey;
    private final String baseUrl;

    public LangChain4jModelFactory(
            @Value("${bailian.api-key:}") String apiKey,
            @Value("${bailian.chat-url}") String chatUrl
    ) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(chatUrl);
    }

    public ChatModel create(String modelName, Duration timeout) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder())
                .baseUrl(baseUrl)
                .apiKey(apiKey == null || apiKey.isBlank() ? UNCONFIGURED_KEY : apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .maxRetries(1)
                .parallelToolCalls(false)
                .build();
    }

    static String normalizeBaseUrl(String chatUrl) {
        if (chatUrl == null || chatUrl.isBlank()) {
            throw new IllegalArgumentException("bailian.chat-url 不能为空");
        }
        String normalized = chatUrl.trim().replaceFirst("/+$", "");
        return normalized.replaceFirst("/chat/completions$", "");
    }
}
