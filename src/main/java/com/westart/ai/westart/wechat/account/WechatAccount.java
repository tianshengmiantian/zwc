package com.westart.ai.westart.wechat.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "wechat_accounts")
class WechatAccount {

    @Id
    @Column(name = "account_id", nullable = false, length = 40)
    private String accountId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WechatAccount() {
    }

    WechatAccount(String accountId, String displayName, Instant createdAt) {
        this.accountId = accountId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    String getAccountId() {
        return accountId;
    }

    String getDisplayName() {
        return displayName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
