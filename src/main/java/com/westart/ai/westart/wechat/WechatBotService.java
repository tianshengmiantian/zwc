package com.westart.ai.westart.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.westart.ai.westart.service.ApiIntegrationException;
import com.westart.ai.westart.service.BailianService;
import com.westart.ai.westart.service.QWeatherService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);
    private static final int MAX_SEEN_MESSAGE_IDS = 1_000;
    private static final int MAX_REPLY_CHARS = 1_800;
    private static final int MAX_QR_REFRESHES = 3;

    private final BailianService bailianService;
    private final QWeatherService weatherService;
    private final boolean enabled;
    private final String defaultLocation;
    private final String botToken;
    private final String userId;
    private final String botId;
    private final String baseUrl;
    private final String adminKey;

    private final AtomicReference<ILinkClient> client = new AtomicReference<>();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean loginPolling = new AtomicBoolean(false);
    private final IlinkQrLoginClient qrLoginClient = new IlinkQrLoginClient();
    private final ExecutorService loginExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("wechat-ilink-login-", 0).factory()
    );
    private final ExecutorService pollingExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("wechat-ilink-poll-", 0).factory()
    );
    private final ExecutorService messageExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Long, Boolean> seenMessageIds = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_SEEN_MESSAGE_IDS + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > MAX_SEEN_MESSAGE_IDS;
                }
            }
    );

    private volatile Future<?> pollingTask;
    private volatile Future<?> loginTask;
    private volatile String loginQrcode;
    private volatile String loginPollingBaseUrl = IlinkQrLoginClient.LOGIN_BASE_URL;
    private volatile String qrCodeContent;
    private volatile String pendingVerificationCode;
    private volatile String loginStatus = "NOT_LOGIN";
    private volatile String loginMessage;
    private volatile boolean verificationRequired;
    private volatile long qrCodeVersion;
    private volatile String lastError;
    private volatile boolean restoredFromEnvironment;

    public WechatBotService(
            BailianService bailianService,
            QWeatherService weatherService,
            @Value("${wechat.bot.enabled:true}") boolean enabled,
            @Value("${wechat.bot.default-location:北京}") String defaultLocation,
            @Value("${wechat.bot.token:}") String botToken,
            @Value("${wechat.bot.user-id:}") String userId,
            @Value("${wechat.bot.bot-id:}") String botId,
            @Value("${wechat.bot.base-url:https://ilinkai.weixin.qq.com}") String baseUrl,
            @Value("${wechat.bot.admin-key:}") String adminKey
    ) {
        this.bailianService = bailianService;
        this.weatherService = weatherService;
        this.enabled = enabled;
        this.defaultLocation = defaultIfBlank(defaultLocation, "北京");
        this.botToken = trim(botToken);
        this.userId = trim(userId);
        this.botId = trim(botId);
        this.baseUrl = defaultIfBlank(baseUrl, "https://ilinkai.weixin.qq.com");
        this.adminKey = trim(adminKey);
    }

    @PostConstruct
    void restoreLoginFromEnvironment() {
        if (!enabled || !hasResumeCredentials()) {
            return;
        }
        synchronized (this) {
            ILinkClient restored = buildClient(new LoginContext(botToken, userId, botId, baseUrl));
            client.set(restored);
            restoredFromEnvironment = true;
            loginStatus = "LOGGED_IN";
            loginMessage = "已从环境变量恢复微信登录";
            startPolling(restored);
        }
        log.info("WeChat iLink session restored from environment variables");
    }

    public synchronized LoginStartResult startLogin() {
        requireEnabled();
        ILinkClient current = client.get();
        if (current != null && current.isLoggedIn()) {
            return new LoginStartResult(true, null, "微信机器人已经登录");
        }

        cancelLoginWorkflow();
        closeCurrentClient();
        restoredFromEnvironment = false;
        lastError = null;
        try {
            IlinkQrLoginClient.QrCodeSession session = qrLoginClient.requestQrCode();
            applyQrSession(session, "请使用手机微信扫描二维码");
            loginPolling.set(true);
            loginTask = loginExecutor.submit(this::qrLoginLoop);
            return new LoginStartResult(
                    false,
                    "/api/wechat/login/qrcode?v=" + qrCodeVersion,
                    "请立即使用手机微信扫码；若手机显示配对数字，请提交到配对码接口"
            );
        } catch (RuntimeException exception) {
            lastError = safeError(exception);
            loginStatus = "ERROR";
            loginMessage = "启动微信扫码登录失败";
            throw new ApiIntegrationException("启动微信扫码登录失败：" + lastError, exception);
        }
    }

    public byte[] loginQrCodePng() {
        String content = qrCodeContent;
        if (content == null || content.isBlank()) {
            throw new ApiIntegrationException("当前没有等待扫描的二维码，请先调用 POST /api/wechat/login");
        }
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 420, 420);
            BufferedImage image = new BufferedImage(420, 420, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", output);
            return output.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new ApiIntegrationException("生成微信登录二维码失败", exception);
        }
    }

    public BotStatus status() {
        ILinkClient current = client.get();
        String connection = current == null
                ? (loginPolling.get() ? "CONNECTING" : "NOT_CONNECTED")
                : current.getConnectionStatus().name();
        boolean loggedIn = current != null && current.isLoggedIn();
        return new BotStatus(
                enabled,
                loggedIn,
                connection,
                loggedIn ? "LOGGED_IN" : loginStatus,
                qrCodeContent != null && !qrCodeContent.isBlank() && !loggedIn,
                verificationRequired,
                restoredFromEnvironment,
                loginMessage,
                lastError
        );
    }

    public BotStatus submitVerificationCode(String code) {
        requireEnabled();
        String normalized = trim(code);
        if (!normalized.matches("\\d{4,8}")) {
            throw new ApiIntegrationException("配对码应为手机微信显示的 4 到 8 位数字");
        }
        if (!loginPolling.get() || !verificationRequired) {
            throw new ApiIntegrationException("当前登录流程不需要配对码");
        }
        pendingVerificationCode = normalized;
        verificationRequired = false;
        loginStatus = "VERIFYING";
        loginMessage = "正在验证手机微信显示的配对码";
        lastError = null;
        return status();
    }

    public synchronized BotStatus disconnect() {
        cancelLoginWorkflow();
        closeCurrentClient();
        qrCodeContent = null;
        loginQrcode = null;
        loginStatus = "NOT_LOGIN";
        loginMessage = "微信机器人已断开";
        verificationRequired = false;
        restoredFromEnvironment = false;
        lastError = null;
        return status();
    }

    public boolean hasAdminKey() {
        return !adminKey.isBlank();
    }

    public boolean adminKeyMatches(String supplied) {
        if (!hasAdminKey() || supplied == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ILinkClient buildClient(LoginContext loginContext) {
        // The SDK heartbeat polls messages without dispatching them to the listener.
        // Disable it and use the explicit polling loop below so no user message is lost.
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(20_000)
                .readTimeoutMs(40_000)
                .writeTimeoutMs(40_000)
                .loginTimeoutMs(180_000)
                .httpMaxRetries(3)
                .heartbeatEnabled(false)
                .build();

        ILinkClientBuilder builder = ILinkClient.builder()
                .config(config)
                .onMessage(messages -> {
                    for (Object value : messages) {
                        if (value instanceof WeixinMessage message) {
                            enqueueMessage(message);
                        }
                    }
                });

        if (loginContext != null) {
            builder.loginContext(loginContext);
        }
        return builder.build();
    }

    private void qrLoginLoop() {
        int refreshCount = 0;
        try {
            while (loginPolling.get() && !Thread.currentThread().isInterrupted()) {
                if (verificationRequired && trim(pendingVerificationCode).isBlank()) {
                    pause(250L);
                    continue;
                }

                IlinkQrLoginClient.QrLoginStatus response = qrLoginClient.pollStatus(
                        loginPollingBaseUrl,
                        loginQrcode,
                        pendingVerificationCode
                );
                String status = trim(response.status()).toLowerCase();
                switch (status) {
                    case "wait" -> {
                        loginStatus = "WAITING";
                        loginMessage = "等待手机微信扫码";
                    }
                    case "scaned" -> {
                        pendingVerificationCode = null;
                        verificationRequired = false;
                        loginStatus = "SCANNED";
                        loginMessage = "二维码已扫描，等待手机确认";
                    }
                    case "need_verifycode" -> {
                        pendingVerificationCode = null;
                        verificationRequired = true;
                        loginStatus = "NEED_VERIFY_CODE";
                        loginMessage = "请输入手机微信显示的配对数字";
                    }
                    case "verify_code_blocked" -> {
                        refreshCount++;
                        if (refreshQrCode(refreshCount, "配对码多次错误，二维码已刷新")) {
                            continue;
                        }
                        return;
                    }
                    case "expired" -> {
                        refreshCount++;
                        if (refreshQrCode(refreshCount, "二维码已过期并自动刷新，请刷新浏览器后重新扫码")) {
                            continue;
                        }
                        return;
                    }
                    case "scaned_but_redirect" -> {
                        loginPollingBaseUrl = IlinkQrLoginClient.redirectedBaseUrl(response.redirectHost());
                        loginStatus = "SCANNED";
                        loginMessage = "二维码已扫描，正在切换登录节点";
                    }
                    case "binded_redirect" -> {
                        loginStatus = "ALREADY_BOUND";
                        loginMessage = "该微信机器人已连接过其他实例；当前没有可恢复的本地凭据";
                        lastError = loginMessage;
                        return;
                    }
                    case "confirmed" -> {
                        finishQrLogin(response);
                        return;
                    }
                    default -> {
                        loginStatus = "WAITING";
                        loginMessage = "等待微信确认，服务端状态：" + (status.isBlank() ? "unknown" : status);
                    }
                }
                pause(1_000L);
            }
        } catch (Exception exception) {
            if (loginPolling.get()) {
                lastError = safeError(exception);
                loginStatus = "ERROR";
                loginMessage = "微信扫码登录失败";
                log.warn("WeChat iLink QR login failed: {}", lastError);
            }
        } finally {
            loginPolling.set(false);
        }
    }

    private boolean refreshQrCode(int refreshCount, String message) {
        if (refreshCount > MAX_QR_REFRESHES) {
            loginStatus = "EXPIRED";
            loginMessage = "二维码多次过期，请重新调用登录接口";
            lastError = loginMessage;
            qrCodeContent = null;
            return false;
        }
        IlinkQrLoginClient.QrCodeSession session = qrLoginClient.requestQrCode();
        applyQrSession(session, message);
        pendingVerificationCode = null;
        verificationRequired = false;
        return true;
    }

    private synchronized void finishQrLogin(IlinkQrLoginClient.QrLoginStatus response) {
        if (isBlank(response.botToken()) || isBlank(response.userId()) || isBlank(response.botId())) {
            throw new ApiIntegrationException("微信确认成功，但登录响应缺少必要凭据");
        }
        LoginContext loginContext = new LoginContext(
                response.botToken(),
                response.userId(),
                response.botId(),
                defaultIfBlank(response.baseUrl(), loginPollingBaseUrl)
        );
        ILinkClient connectedClient = buildClient(loginContext);
        client.set(connectedClient);
        qrCodeContent = null;
        loginQrcode = null;
        pendingVerificationCode = null;
        verificationRequired = false;
        loginStatus = "LOGGED_IN";
        loginMessage = "微信机器人登录成功";
        lastError = null;
        startPolling(connectedClient);
        log.info("WeChat iLink login succeeded");
    }

    private void applyQrSession(IlinkQrLoginClient.QrCodeSession session, String message) {
        loginQrcode = session.qrcode();
        qrCodeContent = session.imageContent();
        loginPollingBaseUrl = session.pollingBaseUrl();
        qrCodeVersion = System.currentTimeMillis();
        loginStatus = "WAITING";
        loginMessage = message;
    }

    private void startPolling(ILinkClient activeClient) {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        pollingTask = pollingExecutor.submit(() -> pollLoop(activeClient));
    }

    private void pollLoop(ILinkClient activeClient) {
        try {
            while (polling.get() && client.get() == activeClient && !Thread.currentThread().isInterrupted()) {
                try {
                    activeClient.getUpdates();
                    lastError = null;
                } catch (Exception exception) {
                    if (!polling.get() || client.get() != activeClient) {
                        break;
                    }
                    lastError = safeError(exception);
                    log.warn("WeChat iLink polling failed: {}", lastError);
                    pauseBeforeRetry();
                }
            }
        } finally {
            if (client.get() == activeClient) {
                polling.compareAndSet(true, false);
            }
        }
    }

    private void enqueueMessage(WeixinMessage message) {
        if (!isNewMessage(message.getMessage_id())) {
            return;
        }
        String fromUserId = trim(message.getFrom_user_id());
        if (fromUserId.isBlank() || fromUserId.endsWith("@im.bot")) {
            return;
        }
        messageExecutor.submit(() -> processMessage(message, fromUserId));
    }

    private void processMessage(WeixinMessage message, String fromUserId) {
        ILinkClient activeClient = client.get();
        if (activeClient == null || !activeClient.isLoggedIn()) {
            return;
        }
        try {
            String answer = routeMessage(activeClient, message);
            if (answer != null && !answer.isBlank()) {
                sendReply(activeClient, fromUserId, answer);
            }
        } catch (Exception exception) {
            String error = safeError(exception);
            log.warn("Failed to process a WeChat message: {}", error);
            try {
                sendReply(activeClient, fromUserId, "处理消息失败：" + error);
            } catch (Exception replyException) {
                log.warn("Failed to send WeChat error reply: {}", safeError(replyException));
            }
        }
    }

    private String routeMessage(ILinkClient activeClient, WeixinMessage message) throws IOException {
        List<MessageItem> items = message.getItem_list();
        if (items == null || items.isEmpty()) {
            return null;
        }

        String text = firstText(items);
        MessageItem image = firstImage(items);
        if (image != null) {
            byte[] bytes = activeClient.downloadImageFromMessageItem(image);
            return bailianService.analyzeImage(bytes, detectImageContentType(bytes), text).answer();
        }

        MessageItem video = firstVideo(items);
        if (video != null) {
            byte[] bytes = activeClient.downloadVideoFromMessageItem(video);
            return bailianService.analyzeVideo(bytes, "video/mp4", text, 1.0).answer();
        }

        MessageItem voice = firstVoice(items);
        if (voice != null) {
            String transcript = trim(voice.getVoice_item().getText());
            if (transcript.isBlank()) {
                return "我收到了语音，但微信这次没有提供语音转文字结果，请改发文字。";
            }
            return answerText(transcript);
        }

        if (!text.isBlank()) {
            return answerText(text);
        }

        if (items.stream().anyMatch(item -> item.getFile_item() != null)) {
            return "我收到了文件，目前先支持文字、天气、图片、视频和可转写的语音。";
        }
        return "暂时无法识别这条消息，请发送文字、图片、视频或语音。";
    }

    private String answerText(String text) {
        if (containsWeatherIntent(text)) {
            String location = extractWeatherLocation(text, defaultLocation);
            return formatWeather(weatherService.current(location));
        }
        return bailianService.askText(text).answer();
    }

    private static String formatWeather(QWeatherService.CurrentWeather weather) {
        return "%s当前%s，温度%s℃，体感%s℃，湿度%s%%，%s%s级，能见度%s公里。".formatted(
                value(weather.location()),
                value(weather.weather()),
                value(weather.temperatureCelsius()),
                value(weather.feelsLikeCelsius()),
                value(weather.humidityPercent()),
                value(weather.windDirection()),
                value(weather.windScale()),
                value(weather.visibilityKm())
        );
    }

    static boolean containsWeatherIntent(String text) {
        return text != null && (text.contains("天气") || text.contains("气温") || text.contains("温度"));
    }

    static String extractWeatherLocation(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String location = text
                .replaceAll("[，。！？?、,!.\\s]", "")
                .replace("今天", "")
                .replace("现在", "")
                .replace("当前", "")
                .replace("实时", "")
                .replace("天气", "")
                .replace("气温", "")
                .replace("温度", "")
                .replace("帮我", "")
                .replace("麻烦", "")
                .replace("请", "")
                .replace("查询", "")
                .replace("查一下", "")
                .replace("查", "")
                .replace("看看", "")
                .replace("告诉我", "")
                .replace("怎么样", "")
                .replace("如何", "")
                .replace("情况", "")
                .replace("预报", "")
                .replace("一下", "")
                .replace("的", "")
                .trim();
        return location.isBlank() ? fallback : location;
    }

    private void sendReply(ILinkClient activeClient, String toUserId, String answer) throws IOException {
        int start = 0;
        boolean first = true;
        while (start < answer.length()) {
            int end = Math.min(answer.length(), start + MAX_REPLY_CHARS);
            String part = answer.substring(start, end);
            if (first) {
                activeClient.sendTextWithTyping(toUserId, part, 300L);
                first = false;
            } else {
                activeClient.sendText(toUserId, part);
            }
            start = end;
        }
    }

    private static String firstText(List<MessageItem> items) {
        for (MessageItem item : items) {
            if (item != null && item.getText_item() != null) {
                String text = trim(item.getText_item().getText());
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static MessageItem firstImage(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getImage_item() != null)
                .findFirst()
                .orElse(null);
    }

    private static MessageItem firstVideo(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getVideo_item() != null)
                .findFirst()
                .orElse(null);
    }

    private static MessageItem firstVoice(List<MessageItem> items) {
        return items.stream()
                .filter(item -> item != null && item.getVoice_item() != null)
                .findFirst()
                .orElse(null);
    }

    private boolean isNewMessage(Long messageId) {
        if (messageId == null) {
            return true;
        }
        synchronized (seenMessageIds) {
            return seenMessageIds.putIfAbsent(messageId, Boolean.TRUE) == null;
        }
    }

    private synchronized void closeCurrentClient() {
        polling.set(false);
        Future<?> task = pollingTask;
        if (task != null) {
            task.cancel(true);
            pollingTask = null;
        }
        ILinkClient current = client.getAndSet(null);
        if (current != null) {
            try {
                current.cancelLogin();
            } catch (Exception ignored) {
                // Login may not have been started.
            }
            try {
                current.close();
            } catch (Exception exception) {
                log.warn("Failed to close WeChat iLink client: {}", safeError(exception));
            }
        }
    }

    private synchronized void cancelLoginWorkflow() {
        loginPolling.set(false);
        Future<?> task = loginTask;
        if (task != null) {
            task.cancel(true);
            loginTask = null;
        }
        pendingVerificationCode = null;
        verificationRequired = false;
    }

    private boolean hasResumeCredentials() {
        return !botToken.isBlank() && !userId.isBlank() && !botId.isBlank();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiIntegrationException("微信机器人已关闭，请把 WECHAT_BOT_ENABLED 设置为 true 后重启");
        }
    }

    private static void pauseBeforeRetry() {
        pause(2_000L);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String detectImageContentType(byte[] bytes) {
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String safeError(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+", "Bearer ***")
                .replaceAll("(?i)(bot[_-]?token[=: ]+)[^,;\\s]+", "$1***");
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "--" : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    void shutdown() {
        cancelLoginWorkflow();
        closeCurrentClient();
        loginExecutor.shutdownNow();
        pollingExecutor.shutdownNow();
        messageExecutor.shutdownNow();
    }

    public record LoginStartResult(boolean alreadyLoggedIn, String qrCodePath, String message) {
    }

    public record BotStatus(
            boolean enabled,
            boolean loggedIn,
            String connectionStatus,
            String loginStatus,
            boolean qrCodeReady,
            boolean verificationRequired,
            boolean restoredFromEnvironment,
            String message,
            String lastError
    ) {
    }
}
