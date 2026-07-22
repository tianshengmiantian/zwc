package com.westart.ai.westart.manual;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Scanner;
import java.util.zip.GZIPInputStream;

/**
 * 根据城市名称、LocationID 或经纬度查询并输出实时天气。
 */
public final class WeatherQuery {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String apiHost;
    private final HttpClient httpClient;

    private WeatherQuery() {
        apiKey = requireEnvironmentVariable("QWEATHER_API_KEY");
        apiHost = normalizeHost(requireEnvironmentVariable("QWEATHER_API_HOST"));
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public static void main(String[] args) {
        try {
            String location = readLocation(args);
            new WeatherQuery().printCurrentWeather(location);
        } catch (Exception exception) {
            System.err.println("查询失败：" + exception.getMessage());
            System.exit(1);
        }
    }

    private void printCurrentWeather(String input) throws IOException, InterruptedException {
        Location location = lookupLocation(input);
        String weatherJson = get("/v7/weather/now?location="
                + encode(location.id()) + "&lang=zh&unit=m");
        requireSuccess(weatherJson, "实时天气");

        String now = nowObject(weatherJson);

        System.out.println();
        System.out.println("===== 实时天气 =====");
        System.out.println("地点：" + location.displayName());
        System.out.println("LocationID：" + location.id());
        printField("天气", now, "text", "-");
        printField("温度", now, "temp", "℃");
        printField("体感温度", now, "feelsLike", "℃");
        printField("湿度", now, "humidity", "%");
        printField("风向", now, "windDir", "");
        printField("风力等级", now, "windScale", "级");
        printField("风速", now, "windSpeed", " km/h");
        printField("降水量", now, "precip", " mm");
        printField("能见度", now, "vis", " km");
        printField("观测时间", now, "obsTime", "");
        printField("数据更新时间", weatherJson, "updateTime", "");
    }

    private Location lookupLocation(String input) throws IOException, InterruptedException {
        String path = "/geo/v2/city/lookup?location=" + encode(input)
                + "&range=cn&number=1&lang=zh";
        String locationJson = get(path);
        requireSuccess(locationJson, "城市搜索");

        String locationObject = firstLocationObject(locationJson);
        String id = requiredJsonValue(locationObject, "id");
        String name = requiredJsonValue(locationObject, "name");
        String adm2 = jsonValue(locationObject, "adm2");
        String adm1 = jsonValue(locationObject, "adm1");
        return new Location(id, name, adm2, adm1);
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + apiHost + path))
                .timeout(TIMEOUT)
                .header("X-QW-Api-Key", apiKey)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
        );

        if (response.statusCode() != 200) {
            throw new IllegalStateException("服务器返回 HTTP " + response.statusCode());
        }
        return decodeResponseBody(response);
    }

    private static String readLocation(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0].trim();
        }

        System.out.print("请输入地点（例如：北京、上海、101010100 或 116.41,39.92）：");
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            String value = scanner.nextLine().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("地点不能为空");
            }
            return value;
        }
    }

    private static void requireSuccess(String json, String apiName) {
        String code = jsonValue(json, "code");
        if (!"200".equals(code)) {
            throw new IllegalStateException(apiName + "接口状态码为 "
                    + (code == null ? "无法解析" : code));
        }
    }

    private static void printField(String label, String json, String key, String unit) {
        String value = jsonValue(json, key);
        System.out.println(label + "：" + (value == null ? "暂无数据" : value + unit));
    }

    private static String nowObject(String json) {
        String marker = "\"now\":{";
        int start = compact(json).indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("响应中缺少 now");
        }
        String compactJson = compact(json);
        start += marker.length() - 1;
        return balancedObject(compactJson, start);
    }

    private static String firstLocationObject(String json) {
        String compactJson = compact(json);
        String marker = "\"location\":[{";
        int start = compactJson.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("未找到匹配的地点");
        }
        start += marker.length() - 1;
        return balancedObject(compactJson, start);
    }

    private static String balancedObject(String json, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < json.length(); index++) {
            char current = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return json.substring(start, index + 1);
            }
        }
        throw new IllegalStateException("天气响应格式不完整");
    }

    private static String requiredJsonValue(String json, String key) {
        String value = jsonValue(json, key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("响应中缺少 " + key);
        }
        return value;
    }

    private static String jsonValue(String json, String key) {
        String prefix = "\"" + key + "\":\"";
        int start = json.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = start; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaped) {
                value.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return value.toString();
            } else {
                value.append(current);
            }
        }
        return null;
    }

    private static String compact(String json) {
        return json.replace("\r", "").replace("\n", "");
    }

    private static String decodeResponseBody(HttpResponse<byte[]> response) throws IOException {
        byte[] body = response.body();
        boolean gzip = response.headers()
                .firstValue("Content-Encoding")
                .map(value -> value.equalsIgnoreCase("gzip"))
                .orElse(false);
        if (!gzip) {
            return new String(body, StandardCharsets.UTF_8);
        }
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("未读取到环境变量 " + name);
        }
        return value;
    }

    private static String normalizeHost(String value) {
        return value.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Location(String id, String name, String adm2, String adm1) {
        private String displayName() {
            StringBuilder result = new StringBuilder(name);
            if (adm2 != null && !adm2.isBlank() && !adm2.equals(name)) {
                result.append("，").append(adm2);
            }
            if (adm1 != null && !adm1.isBlank()
                    && !adm1.equals(name) && !adm1.equals(adm2)) {
                result.append("，").append(adm1);
            }
            return result.toString();
        }
    }
}
