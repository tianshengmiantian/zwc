package com.westart.ai.westart.tool.image;

import com.westart.ai.westart.ai.config.AiModelConfiguration;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 图片生成服务。底层委托给 LangChain4j {@link ImageModel}，
 * 业务层不再直接持有 RestClient 或 DashScope API 细节。
 */
@Service
public class ImageGenerationService {

    private final ImageModel imageModel;

    public ImageGenerationService(
            @Qualifier(AiModelConfiguration.IMAGE_GENERATION_MODEL) ImageModel imageModel
    ) {
        this.imageModel = imageModel;
    }

    /**
     * 使用 LangChain4j {@link ImageModel} 生成图片。
     * <p>
     * {@link com.westart.ai.westart.ai.model.image.DashScopeImageModelAdapter}
     * 已完成异步任务提交、轮询、下载和验证，返回的 {@link Image} 包含 base64 数据。
     */
    public GeneratedImage generate(String prompt) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        if (normalizedPrompt.isBlank()) {
            throw new ApiIntegrationException("图片描述不能为空");
        }

        try {
            Response<dev.langchain4j.data.image.Image> response = imageModel.generate(normalizedPrompt);
            dev.langchain4j.data.image.Image image = response.content();

            // adapter 已将图片字节以 base64 嵌入 Image，无需再次下载
            String base64 = image.base64Data();
            if (base64 == null || base64.isBlank()) {
                throw new ApiIntegrationException("百炼图片生成未返回有效数据");
            }
            byte[] bytes = Base64.getDecoder().decode(base64);

            return new GeneratedImage("qwen-image", bytes, "qwen-image.png");
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼图片生成失败：" + safeMessage(exception), exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public record GeneratedImage(String model, byte[] bytes, String fileName) {
    }
}
