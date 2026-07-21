package com.westart.ai.westart.wechat.login;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.westart.ai.westart.common.exception.ApiIntegrationException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class IlinkQrLoginClient {

    public static final String LOGIN_BASE_URL = "https://ilinkai.weixin.qq.com";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IlinkQrLoginClient() {
    }

    public QrCodeSession requestQrCode() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOGIN_BASE_URL + "/ilink/bot/get_bot_qrcode?bot_type=3"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("iLink-App-ClientVersion", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"local_token_list\":[]}"))
                .build();
        Map<String, Object> response = send(request, "申请微信登录二维码");
        String qrcode = requiredString(response, "qrcode");
        String imageContent = requiredString(response, "qrcode_img_content");
        return new QrCodeSession(qrcode, imageContent, LOGIN_BASE_URL);
    }

    public QrLoginStatus pollStatus(String baseUrl, String qrcode, String verifyCode) {
        StringBuilder url = new StringBuilder(normalizeBaseUrl(baseUrl))
                .append("/ilink/bot/get_qrcode_status?qrcode=")
                .append(encode(qrcode));
        if (verifyCode != null && !verifyCode.isBlank()) {
            url.append("&verify_code=").append(encode(verifyCode));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(45))
                .header("iLink-App-ClientVersion", "1")
                .GET()
                .build();
        Map<String, Object> response = send(request, "查询微信扫码状态");
        return new QrLoginStatus(
                string(response, "status"),
                string(response, "bot_token"),
                string(response, "ilink_user_id"),
                string(response, "ilink_bot_id"),
                string(response, "baseurl"),
                string(response, "redirect_host")
        );
    }

    public static String redirectedBaseUrl(String redirectHost) {
        String host = redirectHost == null ? "" : redirectHost.trim();
        if (host.isBlank() || !host.matches("[A-Za-z0-9.-]+")) {
            throw new ApiIntegrationException("iLink 返回了无效的登录跳转地址");
        }
        return "https://" + host;
    }

    private Map<String, Object> send(HttpRequest request, String action) {
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiIntegrationException(action + "失败，HTTP " + response.statusCode());
            }
            Map<String, Object> body = objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {
                    }
            );
            Object ret = body.get("ret");
            if (ret != null && !"0".equals(String.valueOf(ret))) {
                throw new ApiIntegrationException(action + "失败，iLink 状态码 " + ret);
            }
            return body;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiIntegrationException(action + "已取消", exception);
        } catch (IOException exception) {
            throw new ApiIntegrationException(action + "失败：" + exception.getMessage(), exception);
        }
    }

    private static String requiredString(Map<String, Object> source, String key) {
        String value = string(source, key);
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException("iLink 登录响应缺少 " + key);
        }
        return value;
    }

    private static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return LOGIN_BASE_URL;
        }
        return value.trim().replaceFirst("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record QrCodeSession(String qrcode, String imageContent, String pollingBaseUrl) {
    }

    public record QrLoginStatus(
            String status,
            String botToken,
            String userId,
            String botId,
            String baseUrl,
            String redirectHost
    ) {
    }
}
