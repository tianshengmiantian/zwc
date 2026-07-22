package com.westart.ai.westart.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "conversation_summaries")
public class ConversationSummary {

    @Id
    @Column(name = "conversation_id", length = 160)
    private String conversationId;

    @Lob
    @Column(name = "summary_text", nullable = false)
    private String summaryText;

    @Column(name = "summarized_through_message_id", nullable = false)
    private Long summarizedThroughMessageId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationSummary() {
    }

    public ConversationSummary(
            String conversationId,
            String summaryText,
            Long summarizedThroughMessageId,
            Instant updatedAt
    ) {
        this.conversationId = conversationId;
        this.summaryText = summaryText;
        this.summarizedThroughMessageId = summarizedThroughMessageId;
        this.updatedAt = updatedAt;
    }

    public void update(String summaryText, Long summarizedThroughMessageId, Instant updatedAt) {
        this.summaryText = summaryText;
        this.summarizedThroughMessageId = summarizedThroughMessageId;
        this.updatedAt = updatedAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public Long getSummarizedThroughMessageId() {
        return summarizedThroughMessageId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
