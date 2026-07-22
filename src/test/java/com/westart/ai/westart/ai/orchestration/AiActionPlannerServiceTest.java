package com.westart.ai.westart.ai.orchestration;

import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiActionPlannerServiceTest {

    @Test
    void parsesImageToolCall() {
        ChatModel model = mock(ChatModel.class);
        AiActionPlannerService service = new AiActionPlannerService(
                model,
                new ObjectMapper(),
                "test-key",
                "qwen-flash"
        );
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_1")
                .name("generate_image")
                .arguments("{\"prompt\":\"一只窗边的橘猫\"}")
                .build();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .modelName("qwen-flash")
                .aiMessage(AiMessage.from(toolCall))
                .build());

        AiActionPlannerService.ActionPlan plan = service.plan(List.of(
                new ChatMessage("user", "帮我画一只窗边的橘猫")
        ));

        assertThat(plan.hasActions()).isTrue();
        assertThat(plan.calls()).hasSize(1);
        assertThat(plan.calls().getFirst().name()).isEqualTo(AiActionPlannerService.GENERATE_IMAGE);
        assertThat(plan.calls().getFirst().stringArgument("prompt")).isEqualTo("一只窗边的橘猫");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().toolChoice()).isEqualTo(ToolChoice.AUTO);
        assertThat(captor.getValue().toolSpecifications()).hasSize(4);
    }

    @Test
    void returnsOrdinaryAnswerWhenNoToolIsNeeded() {
        ChatModel model = mock(ChatModel.class);
        AiActionPlannerService service = new AiActionPlannerService(
                model,
                new ObjectMapper(),
                "test-key",
                "qwen-flash"
        );
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .modelName("qwen-flash")
                .aiMessage(AiMessage.from("你好，很高兴见到你。"))
                .build());

        AiActionPlannerService.ActionPlan plan = service.plan(List.of(
                new ChatMessage("user", "你好")
        ));

        assertThat(plan.hasActions()).isFalse();
        assertThat(plan.content()).isEqualTo("你好，很高兴见到你。");
    }
}
