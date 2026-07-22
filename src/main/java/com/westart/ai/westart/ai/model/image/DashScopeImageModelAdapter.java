package com.westart.ai.westart.ai.model.image;

import com.westart.ai.westart.common.exception.ApiIntegrationException;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j {@link ImageModel} 适配器，封装 DashScope qwen-image 异步生成流程。
 * <p>
 * DashScope 的图片生成是异步的：提交任务 → 轮询结果 → 下载图片。
 * 此适配器将整个流程封装在单次 {@link #generate(String)} 调用中。
 */
public class DashScopeImageModelAdapter implements ImageModel {

    private static final long MAX_GENERATED_IMAGE_BYTES = 20_000_000;
    private static final int POLL_INTERVAL_MS = 2_000;
    private static final int MAX_POLL_ATTEMPTS = 90; // 最多等 3 分钟

    private final RestClient restClient;
    private final String apiKey;
    private final String imageUrl;
    private final String modelName;
    private final String imageSize;

    private DashScopeImageModelAdapter(Builder builder) {
        this.restClient = builder.restClient == null
                ? RestClient.builder().build()
                : builder.restClient;
        this.apiKey = builder.apiKey;
        this.imageUrl = builder.imageUrl;
        this.modelName = builder.modelName;
        this.imageSize = builder.imageSize;
    }

    @Override
    public Response<Image> generate(String prompt) {
        requireConfigured();
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        if (normalizedPrompt.isBlank()) {
            throw new ApiIntegrationException("图片描述不能为空");
        }

        // 1. 提交异步生成任务
        Map<String, Object> payload = Map.of(
                "model", modelName,
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(Map.of("text", normalizedPrompt))
                        ))
                ),
                "parameters", Map.of(
                        "size", imageSize,
                        "n", 1,
                        "prompt_extend", true,
                        "watermark", false
                )
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri(imageUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            // 2. 提取结果 URL（可能是直接返回，也可能需要轮询）
            URI resultUri = extractImageUri(response);

            // 3. 下载图片字节
            byte[] bytes = restClient.get()
                    .uri(resultUri)
                    .retrieve()
                    .body(byte[].class);
            validateImage(bytes);

            // 将下载的字节以 base64 形式嵌入 Image，调用方无需再次下载
            String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            Image image = Image.builder()
                    .url(resultUri)
                    .base64Data(base64)
                    .build();

            return Response.from(image);
        } catch (RestClientResponseException exception) {
            throw new ApiIntegrationException(
                    "百炼图片生成调用失败，HTTP " + exception.getStatusCode().value()
                            + "。请检查 API Key、图片模型权限、余额和接口地域。",
                    exception
            );
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼图片生成失败：" + safeMessage(exception), exception);
        }
    }

    private void requireConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 DASHSCOPE_API_KEY");
        }
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new ApiIntegrationException("未配置百炼图片生成接口地址");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new ApiIntegrationException("未配置百炼图片生成模型");
        }
        if (imageSize == null || imageSize.isBlank()) {
            throw new ApiIntegrationException("未配置百炼图片尺寸");
        }
    }

    private static URI extractImageUri(Map<String, Object> response) {
        Object outputValue = response == null ? null : response.get("output");
        if (!(outputValue instanceof Map<?, ?> output)) {
            throw responseError(response, "百炼图片响应中缺少 output");
        }
        Object choicesValue = output.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw responseError(response, "百炼图片响应中缺少 choices");
        }
        Object choiceValue = choices.getFirst();
        if (!(choiceValue instanceof Map<?, ?> choice)) {
            throw responseError(response, "百炼图片响应格式不正确");
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            throw responseError(response, "百炼图片响应中缺少 message");
        }
        Object contentValue = message.get("content");
        if (!(contentValue instanceof List<?> content)) {
            throw responseError(response, "百炼图片响应中缺少 content");
        }
        for (Object itemValue : content) {
            if (itemValue instanceof Map<?, ?> item
                    && item.get("image") instanceof String value
                    && !value.isBlank()) {
                URI uri = URI.create(value);
                if (!"https".equalsIgnoreCase(uri.getScheme())) {
                    throw new ApiIntegrationException("百炼返回了不安全的图片地址");
                }
                return uri;
            }
        }
        throw responseError(response, "百炼图片响应中没有生成结果");
    }

    private static ApiIntegrationException responseError(Map<String, Object> response, String fallback) {
        Object code = response == null ? null : response.get("code");
        Object message = response == null ? null : response.get("message");
        if (message instanceof String text && !text.isBlank()) {
            return new ApiIntegrationException(
                    fallback + "：" + (code == null ? "" : code + " - ") + text
            );
        }
        return new ApiIntegrationException(fallback);
    }

    private static void validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("百炼生成的图片下载结果为空");
        }
        if (bytes.length > MAX_GENERATED_IMAGE_BYTES) {
            throw new ApiIntegrationException("生成图片超过 20 MB，无法发送到微信");
        }
        boolean png = bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G';
        if (!png) {
            throw new ApiIntegrationException("百炼生成结果不是有效的 PNG 图片");
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private RestClient restClient;
        private String apiKey;
        private String imageUrl;
        private String modelName;
        private String imageSize;

        public Builder restClient(RestClient restClient) {
            this.restClient = restClient;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder imageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder imageSize(String imageSize) {
            this.imageSize = imageSize;
            return this;
        }

        public DashScopeImageModelAdapter build() {
            return new DashScopeImageModelAdapter(this);
        }
    }
}
