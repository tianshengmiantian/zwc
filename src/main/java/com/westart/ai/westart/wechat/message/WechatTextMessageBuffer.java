package com.westart.ai.westart.wechat.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Combines short bursts of text from the same WeChat account and user before
 * dispatching them to the AI. Different users always have independent buffers.
 */
public final class WechatTextMessageBuffer implements AutoCloseable {

    public static final Duration DEFAULT_NORMAL_DELAY = Duration.ofSeconds(4);
    public static final Duration DEFAULT_GENERATION_DELAY = Duration.ofSeconds(4);
    public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_MESSAGES = 6;

    private static final Logger log = LoggerFactory.getLogger(WechatTextMessageBuffer.class);

    private final Object monitor = new Object();
    private final Map<String, PendingText> pendingByUser = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long normalDelayNanos;
    private final long generationDelayNanos;
    private final long maxWaitNanos;
    private final int maxMessages;
    private final BiConsumer<String, String> dispatcher;
    private boolean closed;

    public WechatTextMessageBuffer(BiConsumer<String, String> dispatcher) {
        this(
                DEFAULT_NORMAL_DELAY,
                DEFAULT_GENERATION_DELAY,
                DEFAULT_MAX_WAIT,
                DEFAULT_MAX_MESSAGES,
                dispatcher
        );
    }

    WechatTextMessageBuffer(
            Duration normalDelay,
            Duration generationDelay,
            Duration maxWait,
            int maxMessages,
            BiConsumer<String, String> dispatcher
    ) {
        this.normalDelayNanos = requirePositive(normalDelay, "普通消息等待时间").toNanos();
        this.generationDelayNanos = requirePositive(generationDelay, "生成指令等待时间").toNanos();
        this.maxWaitNanos = requirePositive(maxWait, "最长等待时间").toNanos();
        if (maxMessages < 1) {
            throw new IllegalArgumentException("最多合并消息数必须大于 0");
        }
        if (this.maxWaitNanos < Math.min(this.normalDelayNanos, this.generationDelayNanos)) {
            throw new IllegalArgumentException("最长等待时间不能小于单次等待时间");
        }
        this.maxMessages = maxMessages;
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("wechat-text-buffer-", 0).factory()
        );
    }

    public void accept(String userId, String text, boolean generationCommand) {
        String normalizedUserId = normalize(userId);
        String normalizedText = normalize(text);
        if (normalizedUserId.isBlank() || normalizedText.isBlank()) {
            return;
        }

        Dispatch dispatchNow = null;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            long now = System.nanoTime();
            PendingText pending = pendingByUser.computeIfAbsent(
                    normalizedUserId,
                    ignored -> new PendingText(now)
            );
            pending.append(normalizedText);
            pending.generationCommand |= generationCommand;
            pending.version++;
            cancel(pending.future);

            if (pending.messageCount >= maxMessages || now - pending.startedAtNanos >= maxWaitNanos) {
                pendingByUser.remove(normalizedUserId);
                dispatchNow = new Dispatch(normalizedUserId, pending.mergedText());
            } else {
                long quietDelay = pending.generationCommand ? generationDelayNanos : normalDelayNanos;
                long remainingMaxWait = maxWaitNanos - (now - pending.startedAtNanos);
                long delay = Math.max(0L, Math.min(quietDelay, remainingMaxWait));
                long scheduledVersion = pending.version;
                pending.future = scheduler.schedule(
                        () -> flushScheduled(normalizedUserId, scheduledVersion),
                        delay,
                        TimeUnit.NANOSECONDS
                );
            }
        }
        if (dispatchNow != null) {
            dispatch(dispatchNow);
        }
    }

    /** Discards unfinished text, used before an immediate reset command. */
    public void discard(String userId) {
        synchronized (monitor) {
            PendingText pending = pendingByUser.remove(normalize(userId));
            if (pending != null) {
                cancel(pending.future);
            }
        }
    }

    private void flushScheduled(String userId, long expectedVersion) {
        Dispatch dispatch = null;
        synchronized (monitor) {
            PendingText pending = pendingByUser.get(userId);
            if (pending != null && pending.version == expectedVersion) {
                pendingByUser.remove(userId);
                dispatch = new Dispatch(userId, pending.mergedText());
            }
        }
        if (dispatch != null) {
            dispatch(dispatch);
        }
    }

    private void dispatch(Dispatch dispatch) {
        try {
            dispatcher.accept(dispatch.userId(), dispatch.text());
        } catch (RuntimeException exception) {
            log.warn("Failed to dispatch buffered WeChat text: {}", exception.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            for (PendingText pending : pendingByUser.values()) {
                cancel(pending.future);
            }
            pendingByUser.clear();
        }
        scheduler.shutdownNow();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
        return value;
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class PendingText {
        private final long startedAtNanos;
        private final StringBuilder text = new StringBuilder();
        private int messageCount;
        private boolean generationCommand;
        private long version;
        private ScheduledFuture<?> future;

        private PendingText(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }

        private void append(String value) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(value);
            messageCount++;
        }

        private String mergedText() {
            return text.toString();
        }
    }

    private record Dispatch(String userId, String text) {
    }
}
