package com.westart.ai.westart.tool.speech;

import com.westart.ai.westart.ai.config.AiModelConfiguration;
import com.westart.ai.westart.ai.model.speech.TextToSpeechModel;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import com.westart.ai.westart.tool.speech.client.QwenRealtimeTtsClient;

import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns text into an audio file. LangChain4j selects the text and voice;
 * this service executes either Qwen's realtime WebSocket API or the legacy HTTP API.
 */
@Service(AiModelConfiguration.TEXT_TO_SPEECH_MODEL)
public class TextToSpeechService implements TextToSpeechModel {

    static final int MAX_SPEECH_TEXT_CHARS = 1_000;
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    private static final Map<String, String> VOICE_ALIASES = voiceAliases();

    private final RestClient restClient;
    private final QwenRealtimeTtsClient realtimeClient;
    private final String apiKey;
    private final String ttsUrl;
    private final String ttsModel;
    private final String ttsVoice;
    private final int sampleRate;

    @Autowired
    public TextToSpeechService(
            @Value("${bailian.api-key}") String apiKey,
            @Value("${bailian.tts-url:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}") String ttsUrl,
            @Value("${bailian.tts-model:qwen3-tts-flash}") String ttsModel,
            @Value("${bailian.tts-voice:Cherry}") String ttsVoice,
            @Value("${bailian.tts-sample-rate:24000}") int sampleRate
    ) {
        this(createRestClient(), new QwenRealtimeTtsClient(), apiKey, ttsUrl, ttsModel, ttsVoice, sampleRate);
    }

    TextToSpeechService(
            RestClient restClient,
            String apiKey,
            String ttsUrl,
            String ttsModel,
            String ttsVoice,
            int sampleRate
    ) {
        this(restClient, new QwenRealtimeTtsClient(), apiKey, ttsUrl, ttsModel, ttsVoice, sampleRate);
    }

    TextToSpeechService(
            RestClient restClient,
            QwenRealtimeTtsClient realtimeClient,
            String apiKey,
            String ttsUrl,
            String ttsModel,
            String ttsVoice,
            int sampleRate
    ) {
        this.restClient = restClient;
        this.realtimeClient = realtimeClient;
        this.apiKey = apiKey;
        this.ttsUrl = ttsUrl;
        this.ttsModel = ttsModel;
        this.ttsVoice = ttsVoice;
        this.sampleRate = sampleRate;
    }

    private static RestClient createRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    public SynthesizedSpeech synthesize(String sourceText) {
        return synthesize(sourceText, null);
    }

    @Override
    public Response<TextToSpeechModel.SynthesizedSpeech> synthesizeSpeech(String text) {
        return synthesizeSpeech(text, null);
    }

    @Override
    public Response<TextToSpeechModel.SynthesizedSpeech> synthesizeSpeech(
            String text,
            String requestedVoice
    ) {
        SynthesizedSpeech result = synthesize(text, requestedVoice);
        return Response.from(new TextToSpeechModel.SynthesizedSpeech(
                result.model(), result.voice(), result.text(), result.audioBytes(), result.fileExtension()
        ));
    }

    public SynthesizedSpeech synthesize(String sourceText, String requestedVoice) {
        requireApiKey();
        String speechText = normalizeSpeechText(sourceText);
        validateConfiguration();
        String selectedVoice = resolveVoice(requestedVoice, ttsVoice);
        if (isRealtimeModel()) {
            byte[] wav = realtimeClient.synthesize(
                    apiKey,
                    ttsUrl,
                    ttsModel,
                    selectedVoice,
                    speechText,
                    sampleRate
            );
            validateAudioSize(wav);
            return new SynthesizedSpeech(ttsModel, selectedVoice, speechText, wav, "wav");
        }
        Map<String, Object> payload = createPayload(speechText, selectedVoice);
        try {
            Map<String, Object> response = restClient.post()
                    .uri(ttsUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
            });
            String audioUrl = extractAudioUrl(response);
            DownloadedAudio audio = downloadAudio(audioUrl);
            return new SynthesizedSpeech(
                    ttsModel,
                    selectedVoice,
                    speechText,
                    audio.bytes(),
                    audio.fileExtension()
            );
        } catch (RestClientResponseException exception) {
            throw new ApiIntegrationException(
                    "百炼语音合成调用失败，HTTP " + exception.getStatusCode().value()
                            + "。请检查 API Key、TTS 模型、音色和北京地域权限。",
                    exception
            );
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼语音合成请求失败：" + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> createPayload(String speechText, String selectedVoice) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", speechText);
        input.put("voice", selectedVoice);
        if (ttsModel != null && ttsModel.startsWith("qwen3-tts")) {
            input.put("language_type", "Chinese");
        } else {
            input.put("format", "mp3");
            input.put("sample_rate", sampleRate);
        }
        return Map.of("model", ttsModel, "input", input);
    }

    static String normalizeSpeechText(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiIntegrationException("待朗读文字不能为空");
        }
        String text = value
                .replaceAll("(?s)```.*?```", " 代码内容 ")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\[([^]\\r\\n]+)]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", "链接")
                .replaceAll("[*_~>]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) {
            throw new ApiIntegrationException("待朗读文字没有可合成的内容");
        }
        if (text.length() > MAX_SPEECH_TEXT_CHARS) {
            text = text.substring(0, MAX_SPEECH_TEXT_CHARS) + "。内容较长，已朗读前一部分。";
        }
        return text;
    }

    static String resolveVoice(String requestedVoice, String defaultVoice) {
        String fallback = defaultVoice == null || defaultVoice.isBlank() ? "Cherry" : defaultVoice.trim();
        if (requestedVoice == null || requestedVoice.isBlank()) {
            return canonicalVoice(fallback, fallback);
        }
        String requested = requestedVoice.trim();
        return canonicalVoice(requested, requested);
    }

    private static String canonicalVoice(String value, String fallback) {
        String normalized = value.toLowerCase(Locale.ROOT).replace(" ", "");
        return VOICE_ALIASES.getOrDefault(normalized, fallback);
    }

    private static Map<String, String> voiceAliases() {
        Map<String, String> aliases = new HashMap<>();
        registerVoice(aliases, "Cherry", "cherry", "女声", "默认女声", "芊悦", "甜美女声");
        registerVoice(aliases, "Serena", "serena", "温柔女声", "温柔", "苏瑶");
        registerVoice(aliases, "Ethan", "ethan", "男声", "默认男声", "晨煦", "沉稳男声");
        registerVoice(aliases, "Chelsie", "chelsie", "二次元", "二次元女声", "千雪");
        registerVoice(aliases, "Momo", "momo", "活泼", "活泼女声", "茉兔");
        registerVoice(aliases, "Ryan", "ryan");
        registerVoice(aliases, "Jennifer", "jennifer");
        registerVoice(aliases, "Bella", "bella");
        return Map.copyOf(aliases);
    }

    private static void registerVoice(Map<String, String> aliases, String canonical, String... names) {
        aliases.put(canonical.toLowerCase(Locale.ROOT), canonical);
        for (String name : names) {
            aliases.put(name.toLowerCase(Locale.ROOT).replace(" ", ""), canonical);
        }
    }

    private DownloadedAudio downloadAudio(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiIntegrationException("百炼返回的语音地址无效", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new ApiIntegrationException("百炼返回的语音地址不是 HTTP/HTTPS 地址");
        }
        byte[] bytes = restClient.get().uri(uri).retrieve().body(byte[].class);
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("百炼生成的语音文件为空");
        }
        if (bytes.length > MAX_AUDIO_BYTES) {
            throw new ApiIntegrationException("百炼生成的语音超过 20 MB，无法通过微信发送");
        }
        String extension = detectAudioExtension(bytes);
        if (extension == null) {
            throw new ApiIntegrationException("百炼返回的内容不是有效的 MP3/WAV 音频");
        }
        return new DownloadedAudio(bytes, extension);
    }

    private static void validateAudioSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("百炼生成的语音文件为空");
        }
        if (bytes.length > MAX_AUDIO_BYTES) {
            throw new ApiIntegrationException("百炼生成的语音超过 20 MB，无法通过微信发送");
        }
    }

    private boolean isRealtimeModel() {
        return ttsModel != null && ttsModel.toLowerCase(Locale.ROOT).contains("realtime");
    }

    private static String extractAudioUrl(Map<String, Object> response) {
        if (response == null || !(response.get("output") instanceof Map<?, ?> output)) {
            throw new ApiIntegrationException("百炼语音合成响应中缺少 output");
        }
        if (!(output.get("audio") instanceof Map<?, ?> audio)) {
            throw new ApiIntegrationException("百炼语音合成响应中缺少 audio");
        }
        Object url = audio.get("url");
        if (!(url instanceof String text) || text.isBlank()) {
            throw new ApiIntegrationException("百炼语音合成响应中缺少音频下载地址");
        }
        return text.trim();
    }

    private static boolean looksLikeMp3(byte[] bytes) {
        return bytes.length >= 3
                && ((bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || (bytes.length >= 2 && bytes[0] == (byte) 0xFF && (bytes[1] & 0xE0) == 0xE0));
    }

    private static boolean looksLikeWav(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }

    private static String detectAudioExtension(byte[] bytes) {
        if (looksLikeMp3(bytes)) {
            return "mp3";
        }
        if (looksLikeWav(bytes)) {
            return "wav";
        }
        return null;
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 DASHSCOPE_API_KEY");
        }
    }

    private void validateConfiguration() {
        if (ttsUrl == null || ttsUrl.isBlank()) {
            throw new ApiIntegrationException("未配置百炼 TTS 地址");
        }
        if (ttsModel == null || ttsModel.isBlank()) {
            throw new ApiIntegrationException("未配置百炼 TTS 模型");
        }
        if (ttsVoice == null || ttsVoice.isBlank()) {
            throw new ApiIntegrationException("未配置百炼 TTS 音色");
        }
        if (sampleRate < 8_000 || sampleRate > 48_000) {
            throw new ApiIntegrationException("TTS 采样率必须在 8000 到 48000 之间");
        }
    }

    public record SynthesizedSpeech(
            String model,
            String voice,
            String text,
            byte[] audioBytes,
            String fileExtension
    ) {
        public String fileName() {
            return "bailian-voice." + fileExtension;
        }
    }

    private record DownloadedAudio(byte[] bytes, String fileExtension) {
    }
}
