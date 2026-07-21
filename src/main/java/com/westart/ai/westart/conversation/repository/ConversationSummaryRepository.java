package com.westart.ai.westart.conversation.repository;

import com.westart.ai.westart.conversation.entity.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, String> {
}
