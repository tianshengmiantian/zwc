package com.westart.ai.westart.wechat.dedup;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
class ProcessedWechatMessageId implements Serializable {

    @Column(name = "account_id", nullable = false, length = 40)
    private String accountId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    protected ProcessedWechatMessageId() {
    }

    ProcessedWechatMessageId(String accountId, Long messageId) {
        this.accountId = accountId;
        this.messageId = messageId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedWechatMessageId that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId)
                && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, messageId);
    }
}
