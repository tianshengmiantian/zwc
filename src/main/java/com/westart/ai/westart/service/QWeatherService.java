package com.westart.ai.westart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class QWeatherService {

    private final RestClient restClient;
    private final String apiKey;
    private final String apiHost;

    public QWeatherService(
            @Value("${qweather.api-key}") String apiKey,
            @Value("${qweather.api-host}") String apiHost
    ) {
        this.restClient = RestClient.builder().build();
        this.apiKey = apiKey;
        this.apiHost = normalizeHost(apiHost);
    }

    public CurrentWeather current(String query) {
        requireConfiguration();
        if (query == null || query.isBlank()) {
            throw new ApiIntegrationException("location 不能为空");
        }

        Map<String, Object> locationResponse = get("/geo/v2/city/lookup", Map.of(
                "location", query,
                "range", "cn",
                "number", "1",
                "lang", "zh"
        ));
        requireBusinessSuccess(locationResponse, "城市搜索");
        Map<String, Object> location = firstLocation(locationResponse);
        String locationId = stringValue(location, "id");

        Map<String, Object> weatherResponse = get("/v7/weather/now", Map.of(
                "location", locationId,
                "lang", "zh",
                "unit", "m"
        ));
        requireBusinessSuccess(weatherResponse, "实时天气");
        Map<String, Object> now = nowValue(weatherResponse);

        return new CurrentWeather(
                stringValue(location, "name"),
                nullableString(location, "adm2"),
                nullableString(location, "adm1"),
                locationId,
                nullableString(weatherResponse, "updateTime"),
                nullableString(now, "obsTime"),
                nullableString(now, "text"),
                nullableString(now, "temp"),
                nullableString(now, "feelsLike"),
                nullableString(now, "humidity"),
                nullableString(now, "windDir"),
                nullableString(now, "windScale"),
                nullableString(now, "windSpeed"),
                nullableString(now, "precip"),
                nullableString(now, "vis")
        );
    }

    private Map<String, Object> get(String path, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://" + apiHost + path);
        query.forEach(builder::queryParam);
        URI uri = builder.build().encode().toUri();

        try {
            return restClient.get()
                    .uri(uri)
                    .header("X-QW-Api-Key", apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException exception) {
            throw new ApiIntegrationException(
                    "和风天气接口调用失败，HTTP " + exception.getStatusCode().value()
                            + "。请检查 API Key、API Host 和凭据限制。",
                    exception
            );
        } catch (Exception exception) {
            throw new ApiIntegrationException("和风天气请求失败：" + exception.getMessage(), exception);
        }
    }

    private void requireConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 QWEATHER_API_KEY");
        }
        if (apiHost == null || apiHost.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 QWEATHER_API_HOST");
        }
    }

    private static void requireBusinessSuccess(Map<String, Object> response, String apiName) {
        if (response == null) {
            throw new ApiIntegrationException(apiName + "返回了空响应");
        }
        String code = String.valueOf(response.get("code"));
        if (!"200".equals(code)) {
            throw new ApiIntegrationException(apiName + "业务状态码为 " + code);
        }
    }

    private static Map<String, Object> firstLocation(Map<String, Object> source) {
        Object value = source.get("location");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new ApiIntegrationException("未找到该地点");
        }
        Object first = list.getFirst();
        if (!(first instanceof Map<?, ?> map)) {
            throw new ApiIntegrationException("和风天气响应格式不正确");
        }
        return castMap(map);
    }

    private static Map<String, Object> nowValue(Map<String, Object> source) {
        Object value = source.get("now");
        if (!(value instanceof Map<?, ?> map)) {
            throw new ApiIntegrationException("和风天气响应中缺少 now");
        }
        return castMap(map);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private static String stringValue(Map<String, Object> source, String key) {
        String value = nullableString(source, key);
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException("和风天气响应中缺少 " + key);
        }
        return value;
    }

    private static String nullableString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/+$", "");
    }

    public record CurrentWeather(
            String location,
            String city,
            String province,
            String locationId,
            String updateTime,
            String observationTime,
            String weather,
            String temperatureCelsius,
            String feelsLikeCelsius,
            String humidityPercent,
            String windDirection,
            String windScale,
            String windSpeedKmH,
            String precipitationMm,
            String visibilityKm
    ) {
    }
}
