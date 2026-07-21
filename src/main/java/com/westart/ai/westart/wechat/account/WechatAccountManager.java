package com.westart.ai.westart.wechat.account;

import com.westart.ai.westart.ai.chat.BailianService;
import com.westart.ai.westart.ai.model.speech.SpeechRecognitionModel;
import com.westart.ai.westart.ai.model.speech.TextToSpeechModel;
import com.westart.ai.westart.ai.orchestration.AiActionPlannerService;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import com.westart.ai.westart.conversation.service.ConversationService;
import com.westart.ai.westart.tool.document.DocumentExtractionService;
import com.westart.ai.westart.tool.image.ImageGenerationService;
import com.westart.ai.westart.tool.search.WebSearchService;
import com.westart.ai.westart.tool.weather.QWeatherService;
import com.westart.ai.westart.wechat.media.WechatVoiceEncodingService;
import com.westart.ai.westart.wechat.dedup.WechatMessageDeduplicator;
import com.westart.ai.westart.wechat.session.WechatBotService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WechatAccountManager {

    public static final String DEFAULT_ACCOUNT_ID = "default";

    private final WechatAccountRepository accountRepository;
    private final BailianService bailianService;
    private final ImageGenerationService imageGenerationService;
    private final SpeechRecognitionModel speechRecognitionModel;
    private final TextToSpeechModel textToSpeechModel;
    private final WechatVoiceEncodingService voiceEncodingService;
    private final WebSearchService webSearchService;
    private final AiActionPlannerService actionPlannerService;
    private final QWeatherService weatherService;
    private final ConversationService conversationService;
    private final DocumentExtractionService documentExtractionService;
    private final WechatMessageDeduplicator messageDeduplicator;
    private final boolean enabled;
    private final String defaultLocation;
    private final String defaultBotToken;
    private final String defaultUserId;
    private final String defaultBotId;
    private final String baseUrl;
    private final String adminKey;
    private final int maxAccounts;
    private final Map<String, WechatBotService> sessions = new ConcurrentHashMap<>();

    public WechatAccountManager(
            WechatAccountRepository accountRepository,
            BailianService bailianService,
            ImageGenerationService imageGenerationService,
            SpeechRecognitionModel speechRecognitionModel,
            TextToSpeechModel textToSpeechModel,
            WechatVoiceEncodingService voiceEncodingService,
            WebSearchService webSearchService,
            AiActionPlannerService actionPlannerService,
            QWeatherService weatherService,
            ConversationService conversationService,
            DocumentExtractionService documentExtractionService,
            WechatMessageDeduplicator messageDeduplicator,
            @Value("${wechat.bot.enabled:true}") boolean enabled,
            @Value("${wechat.bot.default-location:北京}") String defaultLocation,
            @Value("${wechat.bot.token:}") String defaultBotToken,
            @Value("${wechat.bot.user-id:}") String defaultUserId,
            @Value("${wechat.bot.bot-id:}") String defaultBotId,
            @Value("${wechat.bot.base-url:https://ilinkai.weixin.qq.com}") String baseUrl,
            @Value("${wechat.bot.admin-key:}") String adminKey,
            @Value("${wechat.bot.max-accounts:3}") int maxAccounts
    ) {
        this.accountRepository = accountRepository;
        this.bailianService = bailianService;
        this.imageGenerationService = imageGenerationService;
        this.speechRecognitionModel = speechRecognitionModel;
        this.textToSpeechModel = textToSpeechModel;
        this.voiceEncodingService = voiceEncodingService;
        this.webSearchService = webSearchService;
        this.actionPlannerService = actionPlannerService;
        this.weatherService = weatherService;
        this.conversationService = conversationService;
        this.documentExtractionService = documentExtractionService;
        this.messageDeduplicator = messageDeduplicator;
        this.enabled = enabled;
        this.defaultLocation = defaultIfBlank(defaultLocation, "北京");
        this.defaultBotToken = trim(defaultBotToken);
        this.defaultUserId = trim(defaultUserId);
        this.defaultBotId = trim(defaultBotId);
        this.baseUrl = defaultIfBlank(baseUrl, "https://ilinkai.weixin.qq.com");
        this.adminKey = trim(adminKey);
        if (maxAccounts < 1) {
            throw new IllegalArgumentException("微信机器人最大账号数量必须大于 0");
        }
        this.maxAccounts = maxAccounts;
    }

    @PostConstruct
    void initialize() {
        if (!accountRepository.existsById(DEFAULT_ACCOUNT_ID)) {
            accountRepository.save(new WechatAccount(
                    DEFAULT_ACCOUNT_ID,
                    "默认微信机器人",
                    Instant.now()
            ));
        }
        for (WechatAccount account : accountRepository.findAll()) {
            sessions.put(account.getAccountId(), createSession(account.getAccountId()));
        }
        session(DEFAULT_ACCOUNT_ID).restoreLoginFromEnvironment();
    }

    public List<AccountSummary> listAccounts() {
        return accountRepository.findAll().stream()
                .sorted(Comparator.comparing(WechatAccount::getCreatedAt))
                .map(account -> new AccountSummary(
                        account.getAccountId(),
                        account.getDisplayName(),
                        session(account.getAccountId()).status()
                ))
                .toList();
    }

    public synchronized AccountSummary createAccount(String accountId, String displayName) {
        String normalizedId = normalizeNewAccountId(accountId);
        if (accountRepository.existsById(normalizedId)) {
            throw new ApiIntegrationException("微信机器人账号编号已存在：" + normalizedId);
        }
        if (accountRepository.count() >= maxAccounts) {
            throw new ApiIntegrationException(
                    "微信机器人账号数量已达到上限 " + maxAccounts
                            + "，可通过 WECHAT_BOT_MAX_ACCOUNTS 调整"
            );
        }
        WechatAccount account = accountRepository.save(new WechatAccount(
                normalizedId,
                normalizeDisplayName(displayName, normalizedId),
                Instant.now()
        ));
        WechatBotService service = createSession(normalizedId);
        sessions.put(normalizedId, service);
        return new AccountSummary(
                account.getAccountId(),
                account.getDisplayName(),
                service.status()
        );
    }

    public synchronized void removeAccount(String accountId) {
        String normalizedId = normalizeExistingAccountId(accountId);
        if (DEFAULT_ACCOUNT_ID.equals(normalizedId)) {
            throw new ApiIntegrationException("默认微信机器人账号不能删除");
        }
        WechatBotService service = session(normalizedId);
        service.shutdown();
        sessions.remove(normalizedId);
        accountRepository.deleteById(normalizedId);
    }

    public WechatBotService session(String accountId) {
        String normalizedId = normalizeExistingAccountId(accountId);
        WechatBotService service = sessions.get(normalizedId);
        if (service == null) {
            throw new ApiIntegrationException("未找到微信机器人账号：" + normalizedId);
        }
        return service;
    }

    public WechatBotService defaultSession() {
        return session(DEFAULT_ACCOUNT_ID);
    }

    public int maxAccounts() {
        return maxAccounts;
    }

    public boolean hasAdminKey() {
        return !adminKey.isBlank();
    }

    public boolean adminKeyMatches(String supplied) {
        if (!hasAdminKey() || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    private WechatBotService createSession(String accountId) {
        boolean defaultAccount = DEFAULT_ACCOUNT_ID.equals(accountId);
        return new WechatBotService(
                accountId,
                bailianService,
                imageGenerationService,
                speechRecognitionModel,
                textToSpeechModel,
                voiceEncodingService,
                webSearchService,
                actionPlannerService,
                weatherService,
                conversationService,
                documentExtractionService,
                messageDeduplicator,
                enabled,
                defaultLocation,
                defaultAccount ? defaultBotToken : "",
                defaultAccount ? defaultUserId : "",
                defaultAccount ? defaultBotId : "",
                baseUrl
        );
    }

    private static String normalizeNewAccountId(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException("accountId 不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,40}")) {
            throw new ApiIntegrationException(
                    "accountId 只能包含小写字母、数字、下划线和短横线，最多 40 个字符"
            );
        }
        return normalized;
    }

    private static String normalizeExistingAccountId(String value) {
        return normalizeNewAccountId(value);
    }

    private static String normalizeDisplayName(String value, String fallback) {
        String normalized = defaultIfBlank(value, fallback);
        if (normalized.length() > 80) {
            throw new ApiIntegrationException("displayName 不能超过 80 个字符");
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @PreDestroy
    void shutdown() {
        for (WechatBotService service : sessions.values()) {
            service.shutdown();
        }
        sessions.clear();
    }

    public record AccountSummary(
            String accountId,
            String displayName,
            WechatBotService.BotStatus status
    ) {
    }
}
