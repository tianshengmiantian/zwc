package com.westart.ai.westart.conversation.service;

import com.westart.ai.westart.ai.chat.BailianService;
import com.westart.ai.westart.ai.chat.BailianService.AiResult;
import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import com.westart.ai.westart.conversation.entity.ConversationMessage;
import com.westart.ai.westart.conversation.entity.ConversationSummary;
import com.westart.ai.westart.conversation.repository.ConversationMessageRepository;
import com.westart.ai.westart.conversation.repository.ConversationSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationSummaryServiceTest {

    @Mock
    private BailianService bailianService;

    @Mock
    private ConversationMessageRepository messageRepository;

    @Mock
    private ConversationSummaryRepository summaryRepository;

    private ConversationSummaryService summaryService;

    @BeforeEach
    void setUp() {
        summaryService = new ConversationSummaryService(
                bailianService,
                messageRepository,
                summaryRepository,
                10,
                1200
        );
    }

    @Test
    void keepsSmallConversationVerbatimWithoutSummaryCall() {
        List<ConversationMessage> messages = messages(1, 14);
        when(summaryRepository.findById("wechat:user-1")).thenReturn(Optional.empty());
        when(messageRepository.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                "wechat:user-1", 0L
        )).thenReturn(messages);

        List<ChatMessage> context = summaryService.loadRememberedContext("wechat:user-1", 12);

        assertThat(context).hasSize(14);
        verify(bailianService, never()).askText(anyList());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizesExpiredMessagesAndKeepsLatestTwelveVerbatim() {
        List<ConversationMessage> messages = messages(1, 22);
        when(summaryRepository.findById("wechat:user-1")).thenReturn(Optional.empty());
        when(messageRepository.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                "wechat:user-1", 0L
        )).thenReturn(messages);
        when(bailianService.askText(anyList())).thenReturn(
                new AiResult("qwen-flash", "用户正在开发微信 AI，偏好简洁回答。")
        );
        when(summaryRepository.save(any(ConversationSummary.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<ChatMessage> context = summaryService.loadRememberedContext("wechat:user-1", 12);

        assertThat(context).hasSize(13);
        assertThat(context.getFirst().role()).isEqualTo("system");
        assertThat(context.getFirst().content()).contains("用户正在开发微信 AI");
        assertThat(context.subList(1, context.size()))
                .extracting(ChatMessage::content)
                .containsExactlyElementsOf(
                        messages.subList(10, 22).stream()
                                .map(ConversationMessage::getContent)
                                .toList()
                );

        ArgumentCaptor<ConversationSummary> saved = ArgumentCaptor.forClass(ConversationSummary.class);
        verify(summaryRepository).save(saved.capture());
        assertThat(saved.getValue().getSummarizedThroughMessageId()).isEqualTo(10L);

        ArgumentCaptor<List<ChatMessage>> summaryRequest = ArgumentCaptor.forClass(List.class);
        verify(bailianService).askText(summaryRequest.capture());
        assertThat(summaryRequest.getValue()).hasSize(2);
        assertThat(summaryRequest.getValue().getLast().content())
                .contains("消息-1")
                .contains("消息-10")
                .doesNotContain("消息-11");
    }

    private static List<ConversationMessage> messages(int firstId, int lastId) {
        List<ConversationMessage> result = new ArrayList<>();
        for (long id = firstId; id <= lastId; id++) {
            ConversationMessage message = new ConversationMessage(
                    "wechat:user-1",
                    id % 2 == 0 ? "assistant" : "user",
                    "消息-" + id,
                    Instant.now()
            );
            ReflectionTestUtils.setField(message, "id", id);
            result.add(message);
        }
        return result;
    }
}
