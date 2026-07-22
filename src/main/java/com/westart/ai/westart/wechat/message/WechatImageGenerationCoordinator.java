package com.westart.ai.westart.wechat.message;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the latest image-generation instruction per user. A continuation can
 * invalidate an in-flight generation before it is delivered and create one
 * updated instruction instead of falling through to normal chat.
 */
public final class WechatImageGenerationCoordinator {

    public static final Duration DEFAULT_REVISION_WINDOW = Duration.ofMinutes(2);

    private final Map<String, GenerationState> states = new HashMap<>();
    private final long revisionWindowNanos;

    public WechatImageGenerationCoordinator() {
        this(DEFAULT_REVISION_WINDOW);
    }

    WechatImageGenerationCoordinator(Duration revisionWindow) {
        if (revisionWindow == null || revisionWindow.isZero() || revisionWindow.isNegative()) {
            throw new IllegalArgumentException("绘图修改窗口必须大于 0");
        }
        this.revisionWindowNanos = revisionWindow.toNanos();
    }

    public synchronized GenerationTicket begin(String userId, String instruction) {
        String normalizedUserId = normalize(userId);
        String normalizedInstruction = normalize(instruction);
        if (normalizedUserId.isBlank() || normalizedInstruction.isBlank()) {
            throw new IllegalArgumentException("用户和绘图指令不能为空");
        }
        long now = System.nanoTime();
        GenerationState state = states.computeIfAbsent(
                normalizedUserId,
                ignored -> new GenerationState()
        );
        state.version++;
        state.instruction = normalizedInstruction;
        state.updatedAtNanos = now;
        state.delivered = false;
        return new GenerationTicket(normalizedUserId, state.version, normalizedInstruction);
    }

    /**
     * Returns the complete revised instruction when the text belongs to the
     * latest image task, or {@code null} when it should remain normal chat.
     */
    public synchronized String reviseIfRecent(String userId, String continuation) {
        String normalizedUserId = normalize(userId);
        String normalizedContinuation = normalize(continuation);
        GenerationState state = states.get(normalizedUserId);
        long now = System.nanoTime();
        if (state == null || normalizedContinuation.isBlank()
                || now - state.updatedAtNanos > revisionWindowNanos) {
            states.remove(normalizedUserId);
            return null;
        }
        state.version++;
        state.instruction = state.instruction + "\n" + normalizedContinuation;
        state.updatedAtNanos = now;
        state.delivered = false;
        return state.instruction;
    }

    /**
     * Atomically commits the current result for delivery. A revised, stale
     * generation cannot claim delivery and is silently discarded.
     */
    public synchronized boolean claimDelivery(GenerationTicket ticket) {
        GenerationState state = stateFor(ticket);
        if (state == null || state.delivered) {
            return false;
        }
        state.delivered = true;
        state.updatedAtNanos = System.nanoTime();
        return true;
    }

    public synchronized boolean isCurrent(GenerationTicket ticket) {
        return stateFor(ticket) != null;
    }

    public synchronized void complete(GenerationTicket ticket) {
        GenerationState state = stateFor(ticket);
        if (state != null) {
            state.updatedAtNanos = System.nanoTime();
        }
    }

    public synchronized void clear(String userId) {
        states.remove(normalize(userId));
    }

    public synchronized void clearAll() {
        states.clear();
    }

    private GenerationState stateFor(GenerationTicket ticket) {
        if (ticket == null) {
            return null;
        }
        GenerationState state = states.get(ticket.userId());
        return state != null && state.version == ticket.version() ? state : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class GenerationState {
        private long version;
        private String instruction = "";
        private long updatedAtNanos;
        private boolean delivered;
    }

    public record GenerationTicket(String userId, long version, String instruction) {
    }
}
