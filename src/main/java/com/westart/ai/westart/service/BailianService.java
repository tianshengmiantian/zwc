package com.westart.ai.westart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class BailianService {

    private static final long MAX_LOCAL_MEDIA_BYTES = 7_000_000;
    private static final String DEFAULT_IMAGE_PROMPT = "请详细描述图片内容，并识别其中的重要文字和物体。";
    private static final String DEFAULT_VIDEO_PROMPT = "请概括视频内容、主要事件和关键画面。";

    private final RestClient restClient;
    private final String apiKey;
    private final String chatUrl;
    private final String textModel;
    private final String visionModel;

    public BailianService(
            @Value("${bailian.api-key}") String apiKey,
            @Value("${bailian.chat-url}") String chatUrl,
            @Value("${bailian.text-model}") String textModel,
            @Value("${bailian.vision-model}") String visionModel
    ) {
        this.restClient = RestClient.builder().build();
        this.apiKey = apiKey;
        this.chatUrl = chatUrl;
        this.textModel = textModel;
        this.visionModel = visionModel;
    }

    public AiResult askText(String prompt) {
        requireText(prompt, "prompt");
        Map<String, Object> payload = Map.of(
                "model", textModel,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        return call(payload, textModel);
    }

    public AiResult analyzeImage(MultipartFile file, String prompt) {
        String dataUrl = toDataUrl(file, "image/");
        return analyzeMedia("image_url", "image_url", dataUrl,
                defaultIfBlank(prompt, DEFAULT_IMAGE_PROMPT), null);
    }

    public AiResult analyzeImage(byte[] bytes, String contentType, String prompt) {
        String dataUrl = toDataUrl(bytes, contentType, "image/");
        return analyzeMedia("image_url", "image_url", dataUrl,
                defaultIfBlank(prompt, DEFAULT_IMAGE_PROMPT), null);
    }

    public AiResult analyzeVideo(MultipartFile file, String prompt, Double fps) {
        String dataUrl = toDataUrl(file, "video/");
        return analyzeMedia("video_url", "video_url", dataUrl,
                defaultIfBlank(prompt, DEFAULT_VIDEO_PROMPT), normalizeFps(fps));
    }

    public AiResult analyzeVideo(byte[] bytes, String contentType, String prompt, Double fps) {
        String dataUrl = toDataUrl(bytes, contentType, "video/");
        return analyzeMedia("video_url", "video_url", dataUrl,
                defaultIfBlank(prompt, DEFAULT_VIDEO_PROMPT), normalizeFps(fps));
    }

    public AiResult analyzeMediaUrl(MediaTypeName type, String url, String prompt, Double fps) {
        requireText(url, "url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ApiIntegrationException("媒体 URL 必须以 http:// 或 https:// 开头");
        }
        if (type == MediaTypeName.IMAGE) {
            return analyzeMedia("image_url", "image_url", url,
                    defaultIfBlank(prompt, DEFAULT_IMAGE_PROMPT), null);
        }
        return analyzeMedia("video_url", "video_url", url,
                defaultIfBlank(prompt, DEFAULT_VIDEO_PROMPT), normalizeFps(fps));
    }

    private AiResult analyzeMedia(
            String contentType,
            String mediaField,
            String source,
            String prompt,
            Double fps
    ) {
        Map<String, Object> mediaValue = fps == null
                ? Map.of("url", source)
                : Map.of("url", source, "fps", fps);
        List<Map<String, Object>> content = List.of(
                Map.of("type", contentType, mediaField, mediaValue),
                Map.of("type", "text", "text", prompt)
        );
        Map<String, Object> payload = Map.of(
                "model", visionModel,
                "messages", List.of(Map.of("role", "user", "content", content))
        );
        return call(payload, visionModel);
    }

    private AiResult call(Map<String, Object> payload, String model) {
        requireApiKey();
        try {
            Map<String, Object> response = restClient.post()
                    .uri(chatUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return new AiResult(model, extractAnswer(response));
        } catch (RestClientResponseException exception) {
            throw new ApiIntegrationException(
                    "百炼接口调用失败，HTTP " + exception.getStatusCode().value()
                            + "。请检查 API Key、模型权限和地域地址。",
                    exception
            );
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼接口请求失败：" + exception.getMessage(), exception);
        }
    }

    private static String extractAnswer(Map<String, Object> response) {
        if (response == null) {
            throw new ApiIntegrationException("百炼返回了空响应");
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw new ApiIntegrationException("百炼响应中缺少 choices");
        }
        Object firstChoice = choices.getFirst();
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            throw new ApiIntegrationException("百炼响应格式不正确");
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            throw new ApiIntegrationException("百炼响应中缺少 message");
        }
        Object content = message.get("content");
        if (content instanceof String text && !text.isBlank()) {
            return text;
        }
        if (content != null) {
            return content.toString();
        }
        throw new ApiIntegrationException("百炼响应中缺少回答内容");
    }

    private static String toDataUrl(MultipartFile file, String requiredPrefix) {
        if (file == null || file.isEmpty()) {
            throw new ApiIntegrationException("上传文件不能为空");
        }
        if (file.getSize() > MAX_LOCAL_MEDIA_BYTES) {
            throw new ApiIntegrationException("本地文件不能超过 7 MB；更大的文件请使用 URL 接口");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith(requiredPrefix)) {
            throw new ApiIntegrationException("文件类型不正确，应为 " + requiredPrefix + "* 类型");
        }
        try {
            return "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(file.getBytes());
        } catch (IOException exception) {
            throw new ApiIntegrationException("读取上传文件失败", exception);
        }
    }

    private static String toDataUrl(byte[] bytes, String contentType, String requiredPrefix) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("媒体内容不能为空");
        }
        if (bytes.length > MAX_LOCAL_MEDIA_BYTES) {
            throw new ApiIntegrationException("微信媒体不能超过 7 MB；请发送更小的文件");
        }
        if (contentType == null || !contentType.startsWith(requiredPrefix)) {
            throw new ApiIntegrationException("媒体类型不正确，应为 " + requiredPrefix + "* 类型");
        }
        return "data:" + contentType + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 DASHSCOPE_API_KEY");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException(fieldName + " 不能为空");
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Double normalizeFps(Double fps) {
        if (fps == null) {
            return 1.0;
        }
        if (fps < 0.1 || fps > 10.0) {
            throw new ApiIntegrationException("fps 必须在 0.1 到 10.0 之间");
        }
        return fps;
    }

    public enum MediaTypeName {
        IMAGE,
        VIDEO
    }

    public record AiResult(String model, String answer) {
    }
}
