package com.westart.ai.westart.wechat.media;

import com.westart.ai.westart.common.exception.ApiIntegrationException;

import io.github.kasukusakura.silkcodec.SilkCoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Converts audio synthesized by Bailian to the Tencent SILK format used by
 * WeChat voice bubbles.
 */
@Service
public class WechatVoiceEncodingService {

    static final int WECHAT_SAMPLE_RATE = 16_000;
    private static final int SILK_BIT_RATE = 24_000;
    private final String ffmpegPath;

    public WechatVoiceEncodingService(@Value("${audio.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
    }

    public WechatVoice encodeAudio(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new ApiIntegrationException("待转换的语音不能为空");
        }
        byte[] pcm = transcodeToPcm(audioBytes);
        byte[] silk = encodePcmToSilk(pcm);
        int durationMs = Math.max(1, (int) Math.round(
                pcm.length * 1_000.0 / (WECHAT_SAMPLE_RATE * 2.0)
        ));
        return new WechatVoice(silk, durationMs, WECHAT_SAMPLE_RATE);
    }

    public WechatVoice encodeMp3(byte[] mp3Bytes) {
        return encodeAudio(mp3Bytes);
    }

    byte[] encodePcmToSilk(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            throw new ApiIntegrationException("PCM 音频不能为空");
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(pcm);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            SilkCoder.encode(
                    input,
                    output,
                    WECHAT_SAMPLE_RATE,
                    SILK_BIT_RATE,
                    true,
                    true
            );
            byte[] silk = output.toByteArray();
            if (silk.length == 0) {
                throw new ApiIntegrationException("SILK 编码结果为空");
            }
            return silk;
        } catch (IOException | LinkageError exception) {
            throw new ApiIntegrationException("微信 SILK 语音编码失败：" + exception.getMessage(), exception);
        }
    }

    private byte[] transcodeToPcm(byte[] audioBytes) {
        Process process;
        try {
            process = new ProcessBuilder(
                    ffmpegPath,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", "pipe:0",
                    "-vn",
                    "-ac", "1",
                    "-ar", String.valueOf(WECHAT_SAMPLE_RATE),
                    "-c:a", "pcm_s16le",
                    "-f", "s16le",
                    "pipe:1"
            ).start();
        } catch (IOException exception) {
            throw new ApiIntegrationException(
                    "无法启动 FFmpeg，不能生成微信原生语音；程序将改发 MP3 文件。",
                    exception
            );
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> inputTask = executor.submit(() -> {
                try (var output = process.getOutputStream()) {
                    output.write(audioBytes);
                }
                return null;
            });
            Future<byte[]> outputTask = executor.submit(() -> process.getInputStream().readAllBytes());
            Future<byte[]> errorTask = executor.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ApiIntegrationException("微信语音转换超时，程序将改发 MP3 文件");
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
                if (error.length() > 300) {
                    error = error.substring(0, 300);
                }
                throw new ApiIntegrationException(
                        "微信语音转换失败，程序将改发 MP3 文件"
                                + (error.isBlank() ? "" : "：" + error)
                );
            }
            return pcm;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ApiIntegrationException("微信语音转换被中断", exception);
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            process.destroyForcibly();
            throw new ApiIntegrationException("微信语音转换失败：" + exception.getMessage(), exception);
        }
    }

    public record WechatVoice(byte[] silkBytes, int durationMs, int sampleRate) {
        public String fileName() {
            return "bailian-voice.silk";
        }
    }
}
