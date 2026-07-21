package com.westart.ai.westart.ai.config;

import com.westart.ai.westart.ai.model.image.DashScopeImageModelAdapter;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.image.ImageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Registers every model role with an explicit Spring bean name.
 * Business services depend on a role (text/vision/image), not on model
 * construction details or a concrete provider.
 * <p>
 * LangChain4j provides standard interfaces for ChatModel and ImageModel.
 * For ASR/TTS (no standard LangChain4j interface), the tool services keep
 * their own RestClient-based implementations but follow the same bean-naming
 * convention.
 */
@Configuration
public class AiModelConfiguration {

    public static final String TEXT_CHAT_MODEL = "textChatModel";
    public static final String VISION_CHAT_MODEL = "visionChatModel";
    public static final String IMAGE_GENERATION_MODEL = "imageGenerationModel";
    public static final String SPEECH_RECOGNITION_MODEL = "speechRecognitionModel";
    public static final String TEXT_TO_SPEECH_MODEL = "textToSpeechModel";

    @Bean(TEXT_CHAT_MODEL)
    ChatModel textChatModel(
            LangChain4jModelFactory modelFactory,
            @Value("${bailian.text-model}") String modelName
    ) {
        return modelFactory.create(modelName, Duration.ofSeconds(60));
    }

    @Bean(VISION_CHAT_MODEL)
    ChatModel visionChatModel(
            LangChain4jModelFactory modelFactory,
            @Value("${bailian.vision-model}") String modelName
    ) {
        return modelFactory.create(modelName, Duration.ofSeconds(90));
    }

    @Bean(IMAGE_GENERATION_MODEL)
    ImageModel imageGenerationModel(
            @Value("${bailian.api-key}") String apiKey,
            @Value("${bailian.image-url}") String imageUrl,
            @Value("${bailian.image-model}") String imageModel,
            @Value("${bailian.image-size}") String imageSize
    ) {
        return DashScopeImageModelAdapter.builder()
                .apiKey(apiKey)
                .imageUrl(imageUrl)
                .modelName(imageModel)
                .imageSize(imageSize)
                .build();
    }
}
