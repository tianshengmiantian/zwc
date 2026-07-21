package com.westart.ai.westart.ai.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BailianServiceTest {

    @Test
    void delegatesTextConversationToLangChain4jModel() {
        ChatModel textModel = mock(ChatModel.class);
        ChatModel visionModel = mock(ChatModel.class);
        when(textModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .modelName("qwen3.7-plus")
                .aiMessage(AiMessage.from("你好！"))
                .build());
        BailianService service = new BailianService(
                textModel,
                visionModel,
                "test-key",
                "qwen3.7-plus",
                "qwen3.7-plus"
        );

        BailianService.AiResult result = service.askText("你好");

        assertThat(result.model()).isEqualTo("qwen3.7-plus");
        assertThat(result.answer()).isEqualTo("你好！");
    }
}
