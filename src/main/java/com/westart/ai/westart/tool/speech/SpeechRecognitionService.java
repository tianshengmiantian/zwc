package com.westart.ai.westart.tool.speech;

import com.westart.ai.westart.ai.config.AiModelConfiguration;
import com.westart.ai.westart.ai.model.speech.SpeechRecognitionModel;
import com.westart.ai.westart.common.exception.ApiIntegrationException;

import dev.langchain4j.model.output.Response;
import io.github.kasukusakura.silkcodec.SilkCoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Converts supported audio into a form accepted by Bailian and transcribes it.
 * WeChat native voice messages use SILK, so they are decoded to a 16 kHz WAV first.
 */
@Service(AiModelConfiguration.SPEECH_RECOGNITION_MODEL)
public class SpeechRecognitionService implements SpeechRecognitionModel {

    public static final long MAX_AUDIO_BYTES = 10L * 1024 * 1024;
    static final int MAX_AUDIO_SECONDS = 5 * 60;
    private static final int SILK_OUTPUT_SAMPLE_RATE = 16_000;
    private static final int PCM_BITS_PER_SAMPLE = 16;
    private static final int PCM_CHANNELS = 1;
    private final RestClient restClient;
    private final String apiKey;
    private final String asrUrl;
    private final String asrModel;
    private final String ffmpegPath;

    @Autowired
    public SpeechRecognitionService(
            @Value("${bailian.api-key}") String apiKey,
            @Value("${bailian.asr-url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}") String asrUrl,
            @Value("${bailian.asr-model:qwen3-asr-flash}") String asrModel,
            @Value("${audio.ffmpeg-path:ffmpeg}") String ffmpegPath
    ) {
        this(RestClient.builder().build(), apiKey, asrUrl, asrModel, ffmpegPath);
    }

    SpeechRecognitionService(RestClient restClient, String apiKey, String asrUrl, String asrModel) {
        this(restClient, apiKey, asrUrl, asrModel, "ffmpeg");
    }

    SpeechRecognitionService(
            RestClient restClient,
            String apiKey,
            String asrUrl,
            String asrModel,
            String ffmpegPath
    ) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.asrUrl = asrUrl;
        this.asrModel = asrModel;
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
    }

    public TranscriptionResult transcribeFile(byte[] bytes, String fileName) {
        return transcribe(prepareFileForRecognition(bytes, fileName));
    }

    @Override
    public Response<SpeechRecognitionModel.Transcription> recognizeFile(byte[] bytes, String fileName) {
        TranscriptionResult result = transcribeFile(bytes, fileName);
        return Response.from(new SpeechRecognitionModel.Transcription(result.model(), result.transcript()));
    }

    PreparedAudio prepareFileForRecognition(byte[] bytes, String fileName) {
        PreparedAudio audio = prepareAudio(bytes, fileName, null, null, null);
        return normalizeFileAudio(audio);
    }

    public TranscriptionResult transcribeVoice(
            byte[] bytes,
            Integer encodeType,
            Integer sampleRate,
            Integer bitsPerSample,
            Integer playtimeMs
    ) {
        if (playtimeMs != null && playtimeMs > MAX_AUDIO_SECONDS * 1_000) {
            throw new ApiIntegrationException("语音不能超过 5 分钟");
        }
        PreparedAudio audio = prepareAudio(
                bytes,
                null,
                encodeType,
                sampleRate,
                bitsPerSample
        );
        return transcribe(audio);
    }

    @Override
    public Response<SpeechRecognitionModel.Transcription> recognizeVoice(
            byte[] bytes,
            Integer encodeType,
            Integer sampleRate,
            Integer bitsPerSample,
            Integer playtimeMs
    ) {
        TranscriptionResult result = transcribeVoice(
                bytes, encodeType, sampleRate, bitsPerSample, playtimeMs
        );
        return Response.from(new SpeechRecognitionModel.Transcription(result.model(), result.transcript()));
    }

    @Override
    public boolean isSupportedAudioFile(String fileName, byte[] bytes) {
        AudioFormat namedFormat = AudioFormat.fromExtension(extensionOf(fileName));
        return namedFormat != AudioFormat.UNKNOWN || detectByMagic(bytes) != AudioFormat.UNKNOWN;
    }

    private TranscriptionResult transcribe(PreparedAudio audio) {
        requireApiKey();
        if (audio.bytes().length > MAX_AUDIO_BYTES) {
            throw new ApiIntegrationException("音频不能超过 10 MB；较长录音请先压缩或截短到 5 分钟以内");
        }
        String dataUrl = "data:" + audio.mimeType() + ";base64,"
                + Base64.getEncoder().encodeToString(audio.bytes());
        Map<String, Object> payload = Map.of(
                "model", asrModel,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "input_audio",
                                "input_audio", Map.of("data", dataUrl)
                        ))
                )),
                "stream", false,
                "asr_options", Map.of("enable_itn", true)
        );
        try {
            Map<String, Object> response = restClient.post()
                    .uri(asrUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return new TranscriptionResult(asrModel, extractTranscript(response));
        } catch (RestClientResponseException exception) {
            throw new ApiIntegrationException(
                    buildProviderErrorMessage(exception),
                    exception
            );
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼语音识别请求失败：" + exception.getMessage(), exception);
        }
    }

    private PreparedAudio normalizeFileAudio(PreparedAudio audio) {
        if (!List.of("m4a", "mp4", "mov").contains(audio.extension())) {
            return audio;
        }
        if (audio.bytes().length > MAX_AUDIO_BYTES) {
            throw new ApiIntegrationException("M4A 录音不能超过 10 MB");
        }
        return new PreparedAudio(transcodeToWav(audio.bytes()), "audio/wav", "wav");
    }

    private byte[] transcodeToWav(byte[] source) {
        Process process;
        try {
            process = new ProcessBuilder(
                    ffmpegPath,
                    "-hide_banner",
                    "-loglevel", "error",
                    // Ordinary M4A/MOV files often keep their index at the end of the file.
                    // The cache wrapper makes stdin seekable, so FFmpeg can return to the
                    // earlier audio packets after it has read that index.
                    "-read_ahead_limit", "-1",
                    "-i", "cache:pipe:0",
                    "-t", String.valueOf(MAX_AUDIO_SECONDS),
                    "-vn",
                    "-ac", "1",
                    "-ar", String.valueOf(SILK_OUTPUT_SAMPLE_RATE),
                    "-c:a", "pcm_s16le",
                    "-f", "s16le",
                    "pipe:1"
            ).start();
        } catch (IOException exception) {
            throw new ApiIntegrationException(
                    "无法启动 FFmpeg，因此不能读取 M4A 录音。请安装 FFmpeg，或通过 FFMPEG_PATH 指定程序位置。",
                    exception
            );
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> inputTask = executor.submit(() -> {
                try (var output = process.getOutputStream()) {
                    output.write(source);
                }
                return null;
            });
            Future<byte[]> outputTask = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> errorTask = executor.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ApiIntegrationException("M4A 录音转换超时，请确认文件没有损坏且不超过 5 分钟");
            }
            byte[] pcm = outputTask.get();
            byte[] errorBytes = errorTask.get();
            try {
                inputTask.get();
            } catch (Exception writeFailure) {
                if (process.exitValue() == 0) {
                    throw writeFailure;
                }
            }
            if (process.exitValue() != 0 || pcm.length == 0) {
                String error = new String(errorBytes, StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ")
                        .trim();
                if (error.length() > 400) {
                    error = error.substring(0, 400);
                }
                if (error.contains("moov atom not found")) {
                    throw new ApiIntegrationException(
                            "M4A 文件缺少必要的 moov 索引。微信附件可能没有完整下载，"
                                    + "请重新发送原文件；已下载完整的文件会在进入转换前自动通过长度和 MD5 校验。"
                    );
                }
                throw new ApiIntegrationException(
                        "M4A 录音转换失败" + (error.isBlank() ? "，文件可能已损坏" : "：" + error)
                );
            }
            // FFmpeg cannot seek when it writes WAV directly to stdout, so its RIFF/data
            // lengths may remain 0xFFFFFFFF. Some ASR services reject such a streamed WAV.
            // Emit raw PCM and construct a normal WAV header with exact sizes ourselves.
            return wrapPcmAsWav(
                    pcm,
                    SILK_OUTPUT_SAMPLE_RATE,
                    PCM_BITS_PER_SAMPLE,
                    PCM_CHANNELS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ApiIntegrationException("M4A 录音转换被中断", exception);
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            process.destroyForcibly();
            throw new ApiIntegrationException("M4A 录音转换失败：" + exception.getMessage(), exception);
        }
    }

    static PreparedAudio prepareAudio(
            byte[] bytes,
            String fileName,
            Integer encodeType,
            Integer sampleRate,
            Integer bitsPerSample
    ) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("音频内容不能为空");
        }
        AudioFormat format = detectFormat(bytes, fileName, encodeType);
        if (format == AudioFormat.SILK) {
            return new PreparedAudio(decodeSilkToWav(bytes), "audio/wav", "wav");
        }
        if (format == AudioFormat.PCM) {
            int rate = sampleRate == null || sampleRate <= 0 ? SILK_OUTPUT_SAMPLE_RATE : sampleRate;
            int bits = bitsPerSample == null || bitsPerSample <= 0 ? PCM_BITS_PER_SAMPLE : bitsPerSample;
            if (bits != PCM_BITS_PER_SAMPLE) {
                throw new ApiIntegrationException("暂不支持 " + bits + " 位 PCM 微信语音");
            }
            return new PreparedAudio(wrapPcmAsWav(bytes, rate, bits, PCM_CHANNELS), "audio/wav", "wav");
        }
        if (format == AudioFormat.UNKNOWN || format == AudioFormat.UNSUPPORTED_VOICE) {
            throw new ApiIntegrationException(
                    "不支持该音频格式；请发送 M4A、MP3、WAV、AMR、OGG、Opus、AAC、FLAC 或微信语音"
            );
        }
        return new PreparedAudio(bytes, format.mimeType, format.extension);
    }

    static AudioFormat detectFormat(byte[] bytes, String fileName, Integer encodeType) {
        AudioFormat magic = detectByMagic(bytes);
        if (magic != AudioFormat.UNKNOWN) {
            return magic;
        }
        if (encodeType != null) {
            return switch (encodeType) {
                case 1 -> AudioFormat.PCM;
                case 5 -> AudioFormat.AMR;
                case 6 -> AudioFormat.SILK;
                case 7 -> AudioFormat.MP3;
                case 8 -> AudioFormat.OGG;
                default -> AudioFormat.UNSUPPORTED_VOICE;
            };
        }
        return AudioFormat.fromExtension(extensionOf(fileName));
    }

    private static AudioFormat detectByMagic(byte[] bytes) {
        if (bytes == null) {
            return AudioFormat.UNKNOWN;
        }
        if (startsWith(bytes, "#!SILK_V3".getBytes(StandardCharsets.US_ASCII))
                || (bytes.length > 1 && bytes[0] == 0x02
                && startsWith(bytes, 1, "#!SILK_V3".getBytes(StandardCharsets.US_ASCII)))) {
            return AudioFormat.SILK;
        }
        if (startsWith(bytes, "ID3".getBytes(StandardCharsets.US_ASCII))
                || (bytes.length >= 2 && bytes[0] == (byte) 0xFF && (bytes[1] & 0xE0) == 0xE0)) {
            return AudioFormat.MP3;
        }
        if (startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && bytes.length >= 12
                && startsWith(bytes, 8, "WAVE".getBytes(StandardCharsets.US_ASCII))) {
            return AudioFormat.WAV;
        }
        if (startsWith(bytes, "OggS".getBytes(StandardCharsets.US_ASCII))) {
            return AudioFormat.OGG;
        }
        if (startsWith(bytes, "fLaC".getBytes(StandardCharsets.US_ASCII))) {
            return AudioFormat.FLAC;
        }
        if (startsWith(bytes, "#!AMR".getBytes(StandardCharsets.US_ASCII))) {
            return AudioFormat.AMR;
        }
        if (bytes.length >= 12
                && startsWith(bytes, 4, "ftyp".getBytes(StandardCharsets.US_ASCII))) {
            String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
            if (brand.startsWith("M4A") || brand.startsWith("M4B")) {
                return AudioFormat.M4A;
            }
            if (brand.equals("qt  ")) {
                return AudioFormat.MOV;
            }
            return AudioFormat.MP4;
        }
        return AudioFormat.UNKNOWN;
    }

    private static byte[] decodeSilkToWav(byte[] silkBytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(silkBytes);
             ByteArrayOutputStream pcm = new ByteArrayOutputStream()) {
            SilkCoder.decode(input, pcm, true, SILK_OUTPUT_SAMPLE_RATE, 0);
            return wrapPcmAsWav(
                    pcm.toByteArray(),
                    SILK_OUTPUT_SAMPLE_RATE,
                    PCM_BITS_PER_SAMPLE,
                    PCM_CHANNELS
            );
        } catch (IOException | LinkageError exception) {
            throw new ApiIntegrationException("微信 SILK 语音解码失败：" + exception.getMessage(), exception);
        }
    }

    public static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int bitsPerSample, int channels) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        ByteArrayOutputStream output = new ByteArrayOutputStream(44 + pcm.length);
        try {
            output.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeLittleEndianInt(output, 36 + pcm.length);
            output.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
            writeLittleEndianInt(output, 16);
            writeLittleEndianShort(output, 1);
            writeLittleEndianShort(output, channels);
            writeLittleEndianInt(output, sampleRate);
            writeLittleEndianInt(output, byteRate);
            writeLittleEndianShort(output, blockAlign);
            writeLittleEndianShort(output, bitsPerSample);
            output.write("data".getBytes(StandardCharsets.US_ASCII));
            writeLittleEndianInt(output, pcm.length);
            output.write(pcm);
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("无法构造 WAV 音频", impossible);
        }
    }

    private static String extractTranscript(Map<String, Object> response) {
        if (response == null) {
            throw new ApiIntegrationException("百炼语音识别返回了空响应");
        }
        Object choicesValue = response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw new ApiIntegrationException("百炼语音识别响应中缺少 choices");
        }
        Object firstChoice = choices.getFirst();
        if (!(firstChoice instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new ApiIntegrationException("百炼语音识别响应格式不正确");
        }
        Object content = message.get("content");
        if (content instanceof String transcript && !transcript.isBlank()) {
            return transcript.trim();
        }
        throw new ApiIntegrationException("百炼没有返回可用的语音转写文字");
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 DASHSCOPE_API_KEY");
        }
    }

    private static String buildProviderErrorMessage(RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        String explanation = switch (statusCode) {
            case 400 -> "请求已到达百炼，但音频内容或模型参数未被接受";
            case 401 -> "百炼未通过身份认证，请检查当前运行进程读取到的 API Key";
            case 403 -> "当前 API Key 没有该语音模型的调用权限";
            case 413 -> "音频请求过大";
            case 429 -> "调用过于频繁或额度不足";
            default -> "百炼语音识别服务返回错误";
        };
        String providerDetail = safeProviderDetail(exception.getResponseBodyAsString());
        return "百炼语音识别调用失败，HTTP " + statusCode + "：" + explanation
                + (providerDetail.isBlank() ? "。" : "。服务端信息：" + providerDetail);
    }

    private static String safeProviderDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        String safe = responseBody
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+", "Bearer ***")
                .replaceAll("\\s+", " ")
                .trim();
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 || dot == normalized.length() - 1 ? "" : normalized.substring(dot + 1);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return startsWith(bytes, 0, prefix);
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length - offset < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    enum AudioFormat {
        AAC("audio/aac", "aac"),
        AMR("audio/amr", "amr"),
        AVI("video/x-msvideo", "avi"),
        AIFF("audio/aiff", "aiff"),
        FLAC("audio/flac", "flac"),
        FLV("video/x-flv", "flv"),
        MKV("video/x-matroska", "mkv"),
        M4A("audio/mp4", "m4a"),
        MP3("audio/mpeg", "mp3"),
        MP4("audio/mp4", "mp4"),
        MPEG("audio/mpeg", "mpeg"),
        MOV("audio/quicktime", "mov"),
        OGG("audio/ogg", "ogg"),
        OPUS("audio/opus", "opus"),
        WAV("audio/wav", "wav"),
        WEBM("audio/webm", "webm"),
        WMA("audio/x-ms-wma", "wma"),
        WMV("video/x-ms-wmv", "wmv"),
        SILK("audio/silk", "silk"),
        PCM("audio/pcm", "pcm"),
        UNSUPPORTED_VOICE("application/octet-stream", ""),
        UNKNOWN("application/octet-stream", "");

        private final String mimeType;
        private final String extension;

        AudioFormat(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }

        static AudioFormat fromExtension(String extension) {
            return switch (extension) {
                case "aac" -> AAC;
                case "amr" -> AMR;
                case "avi" -> AVI;
                case "aiff", "aif" -> AIFF;
                case "flac" -> FLAC;
                case "flv" -> FLV;
                case "mkv" -> MKV;
                case "m4a", "m4b" -> M4A;
                case "mp3" -> MP3;
                case "mp4" -> MP4;
                case "mpeg", "mpg" -> MPEG;
                case "mov" -> MOV;
                case "ogg", "oga" -> OGG;
                case "opus" -> OPUS;
                case "wav", "wave" -> WAV;
                case "webm" -> WEBM;
                case "wma" -> WMA;
                case "wmv" -> WMV;
                case "silk", "slk" -> SILK;
                default -> UNKNOWN;
            };
        }
    }

    record PreparedAudio(byte[] bytes, String mimeType, String extension) {
    }

    public record TranscriptionResult(String model, String transcript) {
    }
}
