package com.westart.ai.westart.conversation.service;

import com.westart.ai.westart.ai.chat.BailianService;
import com.westart.ai.westart.ai.chat.BailianService.AiResult;
import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import com.westart.ai.westart.conversation.entity.ConversationMessage;
import com.westart.ai.westart.conversation.repository.ConversationMessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ConversationService {

    private static final int MAX_CONVERSATION_ID_LENGTH = 160;
    private static final Object[] CONVERSATION_LOCKS = createLocks(64);
    private static final Set<String> CLEAR_COMMANDS = Set.of(
            "清空对话", "清除对话", "清空聊天", "清除聊天", "重置对话", "重新开始"
    );

    private final BailianService bailianService;
    private final ConversationMessageRepository repository;
    private final ConversationSummaryService summaryService;
    private final int contextMessageLimit;
    private final int storedMessageLimit;

    public ConversationService(
            BailianService bailianService,
            ConversationMessageRepository repository,
            ConversationSummaryService summaryService,
            @Value("${conversation.context-message-limit:12}") int contextMessageLimit,
            @Value("${conversation.stored-message-limit:200}") int storedMessageLimit
    ) {
        this.bailianService = bailianService;
        this.repository = repository;
        this.summaryService = summaryService;
        this.contextMessageLimit = requirePositive(contextMessageLimit, "上下文消息数量");
        this.storedMessageLimit = Math.max(
                this.contextMessageLimit,
                requirePositive(storedMessageLimit, "保存消息数量")
        );
    }

    public AiResult answer(String conversationId, String prompt) {
        String normalizedId = normalizeConversationId(conversationId);
        requireContent(prompt, "prompt");
        synchronized (lockFor(normalizedId)) {
            List<ChatMessage> messages = loadRecentMessages(normalizedId);
            messages.add(new ChatMessage("user", prompt.trim()));
            AiResult result = bailianService.askText(messages);
            saveExchange(normalizedId, prompt.trim(), result.answer());
            return result;
        }
    }

    /**
     * Builds the same remembered context used by normal chat, but leaves the
     * final answer to the Function Calling router.
     */
    public List<ChatMessage> prepareContext(String conversationId, String prompt) {
        String normalizedId = normalizeConversationId(conversationId);
        requireContent(prompt, "prompt");
        synchronized (lockFor(normalizedId)) {
            List<ChatMessage> messages = loadRecentMessages(normalizedId);
            messages.add(new ChatMessage("user", prompt.trim()));
            return List.copyOf(messages);
        }
    }

    public void rememberExchange(String conversationId, String userContent, String assistantContent) {
        String normalizedId = normalizeConversationId(conversationId);
        requireContent(userContent, "用户消息");
        requireContent(assistantContent, "助手消息");
        synchronized (lockFor(normalizedId)) {
            saveExchange(normalizedId, userContent.trim(), assistantContent.trim());
        }
    }

    public long clear(String conversationId) {
        String normalizedId = normalizeConversationId(conversationId);
        synchronized (lockFor(normalizedId)) {
            long deleted = repository.deleteByConversationId(normalizedId);
            summaryService.clear(normalizedId);
            return deleted;
        }
    }

    public static boolean isClearCommand(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replaceAll("[\\s，。！？!?、,.;；]+", "");
        return CLEAR_COMMANDS.contains(normalized);
    }

    private List<ChatMessage> loadRecentMessages(String conversationId) {
        return new ArrayList<>(
                summaryService.loadRememberedContext(conversationId, contextMessageLimit)
        );
    }

    private void saveExchange(String conversationId, String userContent, String assistantContent) {
        Instant now = Instant.now();
        repository.saveAll(List.of(
                new ConversationMessage(conversationId, "user", userContent, now),
                new ConversationMessage(conversationId, "assistant", assistantContent, now.plusNanos(1))
        ));
        trimStoredMessages(conversationId);
    }

    private void trimStoredMessages(String conversationId) {
        List<ConversationMessage> retained = repository.findByConversationIdOrderByIdDesc(
                conversationId,
                PageRequest.of(0, storedMessageLimit)
        );
        if (retained.size() < storedMessageLimit) {
            return;
        }
        Long oldestRetainedId = retained.getLast().getId();
        repository.deleteByConversationIdAndIdLessThan(conversationId, oldestRetainedId);
    }

    private static String normalizeConversationId(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException("conversationId 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_CONVERSATION_ID_LENGTH) {
            throw new ApiIntegrationException("conversationId 不能超过 160 个字符");
        }
        return normalized;
    }

    private static void requireContent(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException(name + " 不能为空");
        }
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
        return value;
    }

    private static Object lockFor(String conversationId) {
        return CONVERSATION_LOCKS[Math.floorMod(conversationId.hashCode(), CONVERSATION_LOCKS.length)];
    }

    private static Object[] createLocks(int size) {
        Object[] locks = new Object[size];
        for (int index = 0; index < size; index++) {
            locks[index] = new Object();
        }
        return locks;
    }
}
