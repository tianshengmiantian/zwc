package com.westart.ai.westart.wechat.dedup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WechatMessageDeduplicator {

    private static final int CLEANUP_INTERVAL = 100;

    private final ProcessedWechatMessageRepository repository;
    private final int retentionDays;
    private final AtomicInteger acceptedMessages = new AtomicInteger();

    public WechatMessageDeduplicator(
            ProcessedWechatMessageRepository repository,
            @Value("${wechat.message-dedup-retention-days:7}") int retentionDays
    ) {
        this.repository = repository;
        if (retentionDays < 1) {
            throw new IllegalArgumentException("微信消息去重保存天数必须大于 0");
        }
        this.retentionDays = retentionDays;
    }

    public synchronized boolean markIfNew(String accountId, Long messageId) {
        if (messageId == null) {
            return true;
        }
        ProcessedWechatMessageId id = new ProcessedWechatMessageId(accountId, messageId);
        if (repository.existsById(id)) {
            return false;
        }
        try {
            repository.saveAndFlush(new ProcessedWechatMessage(accountId, messageId, Instant.now()));
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
        if (acceptedMessages.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            repository.deleteByProcessedAtBefore(
                    Instant.now().minus(retentionDays, ChronoUnit.DAYS)
            );
        }
        return true;
    }
}
