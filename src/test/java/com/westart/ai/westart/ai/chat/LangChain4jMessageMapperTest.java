package com.westart.ai.westart.ai.chat;

import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jMessageMapperTest {

    @Test
    void mapsAllSupportedRolesInOrder() {
        var messages = LangChain4jMessageMapper.toLangChain4j(List.of(
                new ChatMessage("system", "系统设定"),
                new ChatMessage("user", "你好"),
                new ChatMessage("assistant", "你好！")
        ));

        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> LangChain4jMessageMapper.toLangChain4j(List.of(
                new ChatMessage("tool", "result")
        ))).isInstanceOf(ApiIntegrationException.class);
    }
}
