package com.westart.ai.westart.wechat.dedup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

interface ProcessedWechatMessageRepository
        extends JpaRepository<ProcessedWechatMessage, ProcessedWechatMessageId> {

    @Transactional
    long deleteByProcessedAtBefore(Instant cutoff);
}
