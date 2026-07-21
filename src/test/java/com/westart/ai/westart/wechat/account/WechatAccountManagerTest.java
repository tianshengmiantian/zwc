package com.westart.ai.westart.wechat.account;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WechatAccountManagerTest {

    private static final String TEST_ACCOUNT_ID = "test-bot-2";

    @Autowired
    private WechatAccountManager accountManager;

    @Autowired
    private WechatAccountRepository repository;

    @AfterEach
    void removeTestAccount() {
        if (repository.existsById(TEST_ACCOUNT_ID)) {
            accountManager.removeAccount(TEST_ACCOUNT_ID);
        }
    }

    @Test
    void createsAnIndependentPersistentAccountSession() {
        WechatAccountManager.AccountSummary created = accountManager.createAccount(
                TEST_ACCOUNT_ID,
                "测试机器人二号"
        );

        assertThat(created.accountId()).isEqualTo(TEST_ACCOUNT_ID);
        assertThat(created.displayName()).isEqualTo("测试机器人二号");
        assertThat(repository.existsById(TEST_ACCOUNT_ID)).isTrue();
        assertThat(accountManager.session(TEST_ACCOUNT_ID))
                .isNotSameAs(accountManager.defaultSession());
        assertThat(accountManager.listAccounts())
                .extracting(WechatAccountManager.AccountSummary::accountId)
                .contains(WechatAccountManager.DEFAULT_ACCOUNT_ID, TEST_ACCOUNT_ID);
    }
}
