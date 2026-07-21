package com.westart.ai.westart.manual;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * 手动运行的 API 连通性检查，不会输出任何 API Key。
 */
public final class ApiConnectivityCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public static void main(String[] args) {
        ApiConnectivityCheck check = new ApiConnectivityCheck();

        boolean dashScopeSuccess = check.testDashScope();
        boolean qWeatherSuccess = check.testQWeather();

        System.out.println();
        System.out.println("===== API 测试结果 =====");
        System.out.println("百炼模型: " + resultText(dashScopeSuccess));
        System.out.println("和风天气: " + resultText(qWeatherSuccess));

        if (!dashScopeSuccess || !qWeatherSuccess) {
            System.exit(1);
        }
    }

    private boolean testDashScope() {
        String apiKey = environmentVariable("DASHSCOPE_API_KEY");
        if (apiKey == null) {
            return false;
        }

        String requestBody = """
                {
                  "model": "qwen-flash",
                  "messages": [
                    {"role": "user", "content": "仅回复OK"}
                  ],
                  "max_tokens": 3
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            boolean success = response.statusCode() == 200
                    && response.body().contains("\"choices\"");
            printHttpResult("百炼模型", response.statusCode(), success);
            return success;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("百炼模型: 请求被中断");
            return false;
        } catch (Exception exception) {
            System.out.println("百炼模型: 请求失败（" + exception.getClass().getSimpleName() + "）");
            return false;
        }
    }

    private boolean testQWeather() {
        String apiKey = environmentVariable("QWEATHER_API_KEY");
        if (apiKey == null) {
            return false;
        }

        String configuredHost = System.getenv("QWEATHER_API_HOST");
        String apiHost = configuredHost == null || configuredHost.isBlank()
                ? "devapi.qweather.com"
                : normalizeHost(configuredHost);

        URI uri = URI.create("https://" + apiHost
                + "/v7/weather/now?location=101010100&lang=zh&unit=m");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("X-QW-Api-Key", apiKey)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            String responseBody = decodeResponseBody(response);
            String compactBody = responseBody.replaceAll("\\s+", "");
            boolean success = response.statusCode() == 200
                    && compactBody.contains("\"code\":\"200\"");
            printHttpResult("和风天气", response.statusCode(), success);
            if (!success) {
                System.out.println("和风天气业务状态码: " + extractQWeatherCode(compactBody));
            }
            if (!success && (configuredHost == null || configuredHost.isBlank())) {
                System.out.println("和风天气: 如使用新版凭据，请另外配置 QWEATHER_API_HOST 专属域名");
            }
            return success;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("和风天气: 请求被中断");
            return false;
        } catch (Exception exception) {
            System.out.println("和风天气: 请求失败（" + exception.getClass().getSimpleName() + "）");
            return false;
        }
    }

    private static String environmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.out.println(name + ": 未读取到环境变量");
            return null;
        }
        return value;
    }

    private static String normalizeHost(String value) {
        return value.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/+$", "");
    }

    private static String decodeResponseBody(HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body();
        boolean isGzip = response.headers()
                .firstValue("Content-Encoding")
                .map(value -> value.equalsIgnoreCase("gzip"))
                .orElse(false);

        if (!isGzip) {
            return new String(body, StandardCharsets.UTF_8);
        }

        try (GZIPInputStream gzipInputStream =
                     new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(gzipInputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String extractQWeatherCode(String compactBody) {
        String prefix = "\"code\":\"";
        int start = compactBody.indexOf(prefix);
        if (start < 0) {
            return "无法解析";
        }
        start += prefix.length();
        int end = compactBody.indexOf('"', start);
        return end < 0 ? "无法解析" : compactBody.substring(start, end);
    }

    private static void printHttpResult(String name, int statusCode, boolean success) {
        System.out.println(name + ": HTTP " + statusCode + "，" + resultText(success));
    }

    private static String resultText(boolean success) {
        return success ? "调用成功" : "调用失败";
    }
}
