package com.westart.ai.westart.wechat.dedup;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "wechat_account_processed_messages")
class ProcessedWechatMessage {

    @EmbeddedId
    private ProcessedWechatMessageId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedWechatMessage() {
    }

    ProcessedWechatMessage(String accountId, Long messageId, Instant processedAt) {
        this.id = new ProcessedWechatMessageId(accountId, messageId);
        this.processedAt = processedAt;
    }
}
