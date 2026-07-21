package com.westart.ai.westart.ai.chat;

import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import dev.langchain4j.service.UserMessage;

/**
 * Declarative AI Service for independent, single-prompt text calls.
 * Persistent WeChat conversations continue through ConversationService so the
 * existing H2 history and long-summary mechanism remain the single memory source.
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "textChatModel"
)
public interface StandaloneTextAssistant {

    String chat(@UserMessage String prompt);
}
