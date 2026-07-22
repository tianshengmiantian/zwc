package com.westart.ai.westart.wechat.message;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
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
 * Gives a standalone image a short grace period so a following text message
 * such as "这是什么" can become the image prompt instead of a second request.
 */
public final class WechatImageMessageBuffer implements AutoCloseable {

    public static final Duration DEFAULT_CAPTION_WAIT = Duration.ofSeconds(4);

    private static final Logger log = LoggerFactory.getLogger(WechatImageMessageBuffer.class);

    private final Object monitor = new Object();
    private final Map<String, PendingImage> pendingByUser = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long captionWaitNanos;
    private final BiConsumer<String, WeixinMessage> dispatcher;
    private boolean closed;

    public WechatImageMessageBuffer(BiConsumer<String, WeixinMessage> dispatcher) {
        this(DEFAULT_CAPTION_WAIT, dispatcher);
    }

    WechatImageMessageBuffer(
            Duration captionWait,
            BiConsumer<String, WeixinMessage> dispatcher
    ) {
        if (captionWait == null || captionWait.isZero() || captionWait.isNegative()) {
            throw new IllegalArgumentException("图片说明等待时间必须大于 0");
        }
        this.captionWaitNanos = captionWait.toNanos();
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("wechat-image-buffer-", 0).factory()
        );
    }

    public void accept(String userId, WeixinMessage message) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId.isBlank() || message == null) {
            return;
        }

        PendingImage previous = null;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            previous = pendingByUser.remove(normalizedUserId);
            if (previous != null) {
                cancel(previous.future());
            }
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> flushScheduled(normalizedUserId, message),
                    captionWaitNanos,
                    TimeUnit.NANOSECONDS
            );
            pendingByUser.put(normalizedUserId, new PendingImage(message, future));
        }
        // Never lose the first image when a user sends two images quickly.
        if (previous != null) {
            dispatch(normalizedUserId, previous.message());
        }
    }

    public WeixinMessage take(String userId) {
        synchronized (monitor) {
            PendingImage pending = pendingByUser.remove(normalize(userId));
            if (pending == null) {
                return null;
            }
            cancel(pending.future());
            return pending.message();
        }
    }

    public void discard(String userId) {
        take(userId);
    }

    private void flushScheduled(String userId, WeixinMessage expectedMessage) {
        WeixinMessage message = null;
        synchronized (monitor) {
            PendingImage pending = pendingByUser.get(userId);
            if (pending != null && pending.message() == expectedMessage) {
                pendingByUser.remove(userId);
                message = pending.message();
            }
        }
        if (message != null) {
            dispatch(userId, message);
        }
    }

    private void dispatch(String userId, WeixinMessage message) {
        try {
            dispatcher.accept(userId, message);
        } catch (RuntimeException exception) {
            log.warn("Failed to dispatch buffered WeChat image: {}", exception.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            for (PendingImage pending : pendingByUser.values()) {
                cancel(pending.future());
            }
            pendingByUser.clear();
        }
        scheduler.shutdownNow();
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record PendingImage(WeixinMessage message, ScheduledFuture<?> future) {
    }
}
