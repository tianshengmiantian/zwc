package com.westart.ai.westart.wechat.dedup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WechatMessageDeduplicatorPersistenceTest {

    @Autowired
    private WechatMessageDeduplicator deduplicator;

    @Autowired
    private ProcessedWechatMessageRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void rejectsAMessageThatWasAlreadyStored() {
        assertThat(deduplicator.markIfNew("bot-1", 123456L)).isTrue();
        assertThat(deduplicator.markIfNew("bot-1", 123456L)).isFalse();
        assertThat(deduplicator.markIfNew("bot-2", 123456L)).isTrue();
        assertThat(repository.count()).isEqualTo(2);
    }
}
