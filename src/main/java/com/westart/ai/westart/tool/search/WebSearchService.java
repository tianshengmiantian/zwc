package com.westart.ai.westart.tool.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs web searches and returns results formatted for AI summarization.
 * <p>
 * Default backend is DuckDuckGo Instant Answer API (free, no key required).
 * Set {@code websearch.api-url} and {@code websearch.api-key} to use Serper.dev
 * or another provider. Serper.dev is detected automatically and uses POST.
 */
@Service
public class WebSearchService {

    private static final String DDG_API = "https://api.duckduckgo.com";

    private final RestClient restClient;
    private final String apiUrl;
    private final String apiKey;
    private final boolean usePost;

    public WebSearchService(
            @Value("${websearch.api-url:}") String apiUrl,
            @Value("${websearch.api-key:}") String apiKey
    ) {
        this.restClient = RestClient.builder().build();
        this.apiUrl = normalize(apiUrl);
        this.apiKey = normalize(apiKey);
        this.usePost = this.apiUrl.contains("serper.dev");
    }

    /**
     * Searches the web and returns formatted results suitable for AI processing.
     * Returns empty string on any failure so the caller can fall back gracefully.
     */
    public String search(String query) {
        String q = query == null || query.isBlank() ? "" : query.strip();
        if (q.isBlank()) {
            return "";
        }
        try {
            if (apiKey.isBlank() && DDG_API.equals(apiUrl)) {
                return searchDuckDuckGo(q);
            }
            return usePost ? searchWithPost(q) : searchWithGet(q);
        } catch (Exception e) {
            return "";
        }
    }

    // ---- DuckDuckGo (GET, free, no key) ----

    @SuppressWarnings("unchecked")
    private String searchDuckDuckGo(String query) {
        URI uri = UriComponentsBuilder.fromUriString(DDG_API)
                .queryParam("q", query)
                .queryParam("format", "json")
                .queryParam("no_html", "1")
                .queryParam("skip_disambig", "1")
                .build().encode().toUri();
        Map<String, Object> response = httpGet(uri, null);
        if (response == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String abstractText = stringValue(response.get("AbstractText"));
        if (!abstractText.isBlank()) {
            sb.append(abstractText).append("\n\n");
        }
        Object topics = response.get("RelatedTopics");
        if (topics instanceof List<?> list && !list.isEmpty()) {
            sb.append("相关结果：\n");
            int count = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> topic) {
                    String text = stringValue(topic.get("Text"));
                    String url = stringValue(topic.get("FirstURL"));
                    if (!text.isBlank()) {
                        count++;
                        sb.append(count).append(". ").append(text);
                        if (!url.isBlank()) {
                            sb.append("\n   ").append(url);
                        }
                        sb.append("\n");
                    }
                    if (count >= 8) {
                        break;
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    // ---- Serper.dev (POST) ----

    @SuppressWarnings("unchecked")
    private String searchWithPost(String query) {
        Map<String, Object> body = Map.of("q", query);
        Map<String, Object> response = httpPost(apiUrl, body);
        return formatResults(response);
    }

    // ---- Generic GET ----

    @SuppressWarnings("unchecked")
    private String searchWithGet(String query) {
        URI uri = UriComponentsBuilder.fromUriString(apiUrl)
                .queryParam("q", query)
                .build().encode().toUri();
        Map<String, Object> response = httpGet(uri, apiKey);
        return formatResults(response);
    }

    // ---- Result formatting ----

    private String formatResults(Map<String, Object> response) {
        if (response == null) {
            return "";
        }
        List<?> items = extractResults(response);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String title = stringValue(entry.get("title"));
            String snippet = firstNonBlank(
                    stringValue(entry.get("snippet")),
                    stringValue(entry.get("description")),
                    stringValue(entry.get("content"))
            );
            String url = firstNonBlank(
                    stringValue(entry.get("link")),
                    stringValue(entry.get("url"))
            );
            if (title.isBlank() && snippet.isBlank()) {
                continue;
            }
            count++;
            sb.append(count).append(". ");
            if (!title.isBlank()) {
                sb.append(title).append("\n   ");
            }
            if (!snippet.isBlank()) {
                sb.append(snippet);
            }
            if (!url.isBlank()) {
                sb.append("\n   ").append(url);
            }
            sb.append("\n");
            if (count >= 8) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private List<?> extractResults(Map<String, Object> response) {
        for (String key : List.of("organic", "results", "items")) {
            Object value = response.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return list;
            }
        }
        if (response.get("organic_results") instanceof List<?> list && !list.isEmpty()) {
            return list;
        }
        return List.of();
    }

    // ---- HTTP ----

    private Map<String, Object> httpGet(URI uri, String key) {
        try {
            var spec = restClient.get().uri(uri);
            if (key != null && !key.isBlank()) {
                spec.header("X-API-Key", key);
            }
            return spec.retrieve().body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> httpPost(String url, Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(url)
                    .header("X-API-Key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ---- helpers ----

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString().strip();
        return s.equals("null") ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
