package com.westart.ai.westart.ai.chat;

import com.westart.ai.westart.common.exception.ApiIntegrationException;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;

/**
 * Provider-neutral AI service. LangChain4j now owns chat request construction,
 * serialization and response parsing; callers keep the original stable API.
 */
@Service
public class BailianService {

    private static final long MAX_LOCAL_MEDIA_BYTES = 7_000_000;
    private static final String DEFAULT_IMAGE_PROMPT = "请详细描述图片内容，并识别其中的重要文字和物体。";
    private static final String DEFAULT_VIDEO_PROMPT = "请概括视频内容、主要事件和关键画面。";

    private final String apiKey;
    private final String textModelName;
    private final String visionModelName;
    private final ChatModel textModel;
    private final ChatModel visionModel;
    private final StandaloneTextAssistant standaloneTextAssistant;

    @Autowired
    public BailianService(
            @Qualifier("textChatModel") ChatModel textModel,
            @Qualifier("visionChatModel") ChatModel visionModel,
            StandaloneTextAssistant standaloneTextAssistant,
            @Value("${bailian.api-key:}") String apiKey,
            @Value("${bailian.text-model}") String textModelName,
            @Value("${bailian.vision-model}") String visionModelName
    ) {
        this.apiKey = apiKey;
        this.textModelName = textModelName;
        this.visionModelName = visionModelName;
        this.textModel = textModel;
        this.visionModel = visionModel;
        this.standaloneTextAssistant = standaloneTextAssistant;
    }

    BailianService(
            ChatModel textModel,
            ChatModel visionModel,
            String apiKey,
            String textModelName,
            String visionModelName
    ) {
        this.textModel = textModel;
        this.visionModel = visionModel;
        this.apiKey = apiKey;
        this.textModelName = textModelName;
        this.visionModelName = visionModelName;
        this.standaloneTextAssistant = null;
    }

    public AiResult askText(String prompt) {
        requireText(prompt, "prompt");
        requireApiKey();
        if (standaloneTextAssistant == null) {
            return askText(List.of(new ChatMessage("user", prompt.trim())));
        }
        try {
            return new AiResult(textModelName, standaloneTextAssistant.chat(prompt.trim()));
        } catch (Exception exception) {
            throw integrationFailure("百炼文本模型请求失败", exception);
        }
    }

    public AiResult askText(List<ChatMessage> conversation) {
        requireApiKey();
        try {
            ChatResponse response = textModel.chat(
                    LangChain4jMessageMapper.toLangChain4j(conversation)
            );
            return result(response, textModelName);
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw integrationFailure("百炼文本模型请求失败", exception);
        }
    }

    public AiResult analyzeImage(MultipartFile file, String prompt) {
        LocalMedia media = readMedia(file, "image/");
        return analyzeMedia(
                ImageContent.from(media.base64(), media.contentType()),
                defaultIfBlank(prompt, DEFAULT_IMAGE_PROMPT)
        );
    }

    public AiResult analyzeImage(byte[] bytes, String contentType, String prompt) {
        LocalMedia media = readMedia(bytes, contentType, "image/");
        return analyzeMedia(
                ImageContent.from(media.base64(), media.contentType()),
                defaultIfBlank(prompt, DEFAULT_IMAGE_PROMPT)
        );
    }

    public AiResult analyzeVideo(MultipartFile file, String prompt, Double fps) {
        normalizeFps(fps);
        LocalMedia media = readMedia(file, "video/");
        return analyzeMedia(
                VideoContent.from(media.base64(), media.contentType()),
                defaultIfBlank(prompt, DEFAULT_VIDEO_PROMPT)
        );
    }

    public AiResult analyzeVideo(byte[] bytes, String contentType, String prompt, Double fps) {
        normalizeFps(fps);
        LocalMedia media = readMedia(bytes, contentType, "video/");
        return analyzeMedia(
                VideoContent.from(media.base64(), media.contentType()),
                defaultIfBlank(prompt, DEFAULT_VIDEO_PROMPT)
        );
    }

    public AiResult analyzeMediaUrl(MediaTypeName type, String url, String prompt, Double fps) {
        requireText(url, "url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ApiIntegrationException("媒体 URL 必须以 http:// 或 https:// 开头");
        }
        URI uri = URI.create(url);
        if (type == MediaTypeName.IMAGE) {
            return analyzeMedia(ImageContent.from(uri), defaultIfBlank(prompt, DEFAULT_IMAGE_PROMPT));
        }
        normalizeFps(fps);
        return analyzeMedia(VideoContent.from(uri), defaultIfBlank(prompt, DEFAULT_VIDEO_PROMPT));
    }

    private AiResult analyzeMedia(Content media, String prompt) {
        requireApiKey();
        try {
            UserMessage message = UserMessage.from(List.of(media, TextContent.from(prompt)));
            return result(visionModel.chat(message), visionModelName);
        } catch (Exception exception) {
            throw integrationFailure("百炼多模态模型请求失败", exception);
        }
    }

    private static AiResult result(ChatResponse response, String configuredModel) {
        if (response == null || response.aiMessage() == null
                || response.aiMessage().text() == null || response.aiMessage().text().isBlank()) {
            throw new ApiIntegrationException("百炼响应中缺少回答内容");
        }
        String actualModel = response.modelName();
        return new AiResult(
                actualModel == null || actualModel.isBlank() ? configuredModel : actualModel,
                response.aiMessage().text()
        );
    }

    private static ApiIntegrationException integrationFailure(String prefix, Exception exception) {
        String message = exception.getMessage();
        return new ApiIntegrationException(
                prefix + "：" + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message),
                exception
        );
    }

    private static LocalMedia readMedia(MultipartFile file, String requiredPrefix) {
        if (file == null || file.isEmpty()) {
            throw new ApiIntegrationException("上传文件不能为空");
        }
        try {
            return readMedia(file.getBytes(), file.getContentType(), requiredPrefix);
        } catch (IOException exception) {
            throw new ApiIntegrationException("读取上传文件失败", exception);
        }
    }

    private static LocalMedia readMedia(byte[] bytes, String contentType, String requiredPrefix) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("媒体内容不能为空");
        }
        if (bytes.length > MAX_LOCAL_MEDIA_BYTES) {
            throw new ApiIntegrationException("本地媒体不能超过 7 MB；更大的文件请使用 URL 接口");
        }
        if (contentType == null || !contentType.startsWith(requiredPrefix)) {
            throw new ApiIntegrationException("媒体类型不正确，应为 " + requiredPrefix + "* 类型");
        }
        return new LocalMedia(Base64.getEncoder().encodeToString(bytes), contentType);
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
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static double normalizeFps(Double fps) {
        double normalized = fps == null ? 1.0 : fps;
        if (normalized < 0.1 || normalized > 10.0) {
            throw new ApiIntegrationException("fps 必须在 0.1 到 10.0 之间");
        }
        return normalized;
    }

    private record LocalMedia(String base64, String contentType) {
    }

    public enum MediaTypeName {
        IMAGE,
        VIDEO
    }

    public record AiResult(String model, String answer) {
    }

    public record ChatMessage(String role, String content) {
    }
}
