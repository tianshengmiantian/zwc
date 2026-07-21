package com.westart.ai.westart.conversation.service;

import com.westart.ai.westart.ai.chat.BailianService;
import com.westart.ai.westart.ai.chat.BailianService.AiResult;
import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import com.westart.ai.westart.conversation.entity.ConversationMessage;
import com.westart.ai.westart.conversation.repository.ConversationMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private BailianService bailianService;

    @Mock
    private ConversationMessageRepository repository;

    @Mock
    private ConversationSummaryService summaryService;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                bailianService,
                repository,
                summaryService,
                12,
                200
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsPreviousMessagesAndNewQuestionToBailian() {
        when(summaryService.loadRememberedContext("wechat:user-1", 12)).thenReturn(List.of(
                new ChatMessage("user", "你叫什么？"),
                new ChatMessage("assistant", "我叫小西。")
        ));
        when(bailianService.askText(anyList())).thenReturn(new AiResult("qwen-flash", "你刚才叫我小西。"));

        conversationService.answer("wechat:user-1", "我刚才怎么称呼你？");

        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(bailianService).askText(messages.capture());
        assertThat(messages.getValue()).containsExactly(
                new ChatMessage("user", "你叫什么？"),
                new ChatMessage("assistant", "我叫小西。"),
                new ChatMessage("user", "我刚才怎么称呼你？")
        );
    }

    @Test
    void recognizesClearConversationCommands() {
        assertThat(ConversationService.isClearCommand("清空对话")).isTrue();
        assertThat(ConversationService.isClearCommand("清空对话！")).isTrue();
        assertThat(ConversationService.isClearCommand("普通问题")).isFalse();
    }

    @Test
    void clearsMessagesAndLongTermSummaryTogether() {
        when(repository.deleteByConversationId("wechat:user-1")).thenReturn(8L);

        long deleted = conversationService.clear("wechat:user-1");

        assertThat(deleted).isEqualTo(8L);
        verify(summaryService).clear("wechat:user-1");
    }
}
