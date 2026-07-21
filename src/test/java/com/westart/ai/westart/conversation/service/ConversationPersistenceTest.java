package com.westart.ai.westart.conversation.service;

import com.westart.ai.westart.conversation.entity.ConversationMessage;
import com.westart.ai.westart.conversation.entity.ConversationSummary;
import com.westart.ai.westart.conversation.repository.ConversationMessageRepository;
import com.westart.ai.westart.conversation.repository.ConversationSummaryRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConversationPersistenceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMessageRepository repository;

    @Autowired
    private ConversationSummaryRepository summaryRepository;

    @BeforeEach
    void cleanDatabase() {
        summaryRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void remembersAndClearsAnExchange() {
        conversationService.rememberExchange("wechat:user-2", "你好", "你好，有什么可以帮你？");
        summaryRepository.save(new ConversationSummary(
                "wechat:user-2",
                "用户曾经打过招呼。",
                1L,
                Instant.now()
        ));

        List<ConversationMessage> messages = repository.findByConversationIdOrderByIdDesc(
                "wechat:user-2",
                PageRequest.of(0, 10)
        );
        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(ConversationMessage::getRole)
                .containsExactly("assistant", "user");

        long deleted = conversationService.clear("wechat:user-2");
        assertThat(deleted).isEqualTo(2);
        assertThat(repository.findAll()).isEmpty();
        assertThat(summaryRepository.findAll()).isEmpty();
    }
}
