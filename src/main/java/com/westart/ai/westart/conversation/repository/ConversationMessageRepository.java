package com.westart.ai.westart.conversation.repository;

import com.westart.ai.westart.conversation.entity.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findByConversationIdOrderByIdDesc(
            String conversationId,
            Pageable pageable
    );

    List<ConversationMessage> findByConversationIdAndIdGreaterThanOrderByIdAsc(
            String conversationId,
            Long id
    );

    @Transactional
    long deleteByConversationId(String conversationId);

    @Transactional
    long deleteByConversationIdAndIdLessThan(String conversationId, Long id);
}
