package com.westart.ai.westart.ai.chat;

import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Locale;

/** Maps the application's provider-neutral conversation record to LangChain4j. */
public final class LangChain4jMessageMapper {

    private LangChain4jMessageMapper() {
    }

    public static List<dev.langchain4j.data.message.ChatMessage> toLangChain4j(
            List<ChatMessage> conversation
    ) {
        if (conversation == null || conversation.isEmpty()) {
            throw new ApiIntegrationException("对话内容不能为空");
        }
        return conversation.stream().map(LangChain4jMessageMapper::toLangChain4j).toList();
    }

    public static dev.langchain4j.data.message.ChatMessage toLangChain4j(ChatMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new ApiIntegrationException("对话消息不能为空");
        }
        String content = message.content().trim();
        String role = message.role() == null ? "" : message.role().trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "system" -> SystemMessage.from(content);
            case "user" -> UserMessage.from(content);
            case "assistant" -> AiMessage.from(content);
            default -> throw new ApiIntegrationException("role 只能是 system、user 或 assistant");
        };
    }
}
