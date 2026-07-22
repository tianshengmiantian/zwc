package com.westart.ai.westart.tool.speech;

import com.westart.ai.westart.common.exception.ApiIntegrationException;
import io.github.kasukusakura.silkcodec.SilkCoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpeechRecognitionServiceTest {

    @Test
    void detectsMp3FromFileNameAndMagicBytes() {
        byte[] unknownBytes = {1, 2, 3};
        assertThat(SpeechRecognitionService.detectFormat(unknownBytes, "录音.mp3", null))
                .isEqualTo(SpeechRecognitionService.AudioFormat.MP3);

        byte[] id3Bytes = "ID3demo".getBytes(StandardCharsets.US_ASCII);
        assertThat(SpeechRecognitionService.detectFormat(id3Bytes, "没有扩展名", null))
                .isEqualTo(SpeechRecognitionService.AudioFormat.MP3);
    }

    @Test
    void wrapsPcmAsAValidWavFile() {
        byte[] wav = SpeechRecognitionService.wrapPcmAsWav(new byte[320], 16_000, 16, 1);

        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(readLittleEndianInt(wav, 4)).isEqualTo(wav.length - 8);
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(new String(wav, 36, 4, StandardCharsets.US_ASCII)).isEqualTo("data");
        assertThat(readLittleEndianInt(wav, 40)).isEqualTo(wav.length - 44);
        assertThat(wav).hasSize(364);
    }

    @Test
    void decodesTencentSilkVoiceToWav() throws Exception {
        byte[] pcmSilence = new byte[16_000 * 2 / 5];
        ByteArrayOutputStream silk = new ByteArrayOutputStream();
        SilkCoder.encode(new ByteArrayInputStream(pcmSilence), silk, 16_000);

        SpeechRecognitionService.PreparedAudio audio = SpeechRecognitionService.prepareAudio(
                silk.toByteArray(),
                null,
                6,
                16_000,
                16
        );

        assertThat(audio.mimeType()).isEqualTo("audio/wav");
        assertThat(new String(audio.bytes(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(audio.bytes().length).isGreaterThan(44);
    }

    @Test
    void rejectsUnsupportedWechatVoiceEncoding() {
        assertThatThrownBy(() -> SpeechRecognitionService.prepareAudio(
                new byte[]{1, 2, 3},
                null,
                4,
                16_000,
                16
        )).isInstanceOf(ApiIntegrationException.class)
                .hasMessageContaining("不支持该音频格式");
    }

    @Test
    void sendsAudioToQwenAsrAndReturnsTranscript() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SpeechRecognitionService service = new SpeechRecognitionService(
                builder.build(),
                "test-key",
                "https://example.test/chat/completions",
                "qwen3-asr-flash"
        );
        server.expect(requestTo("https://example.test/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen3-asr-flash"))
                .andExpect(jsonPath("$.messages[0].content[0].type").value("input_audio"))
                .andExpect(jsonPath("$.messages[0].content[0].input_audio.data")
                        .value(org.hamcrest.Matchers.startsWith("data:audio/mpeg;base64,")))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"北京今天天气怎么样\"}}]}",
                        MediaType.APPLICATION_JSON
                ));

        SpeechRecognitionService.TranscriptionResult result = service.transcribeFile(
                "ID3demo".getBytes(StandardCharsets.US_ASCII),
                "voice.mp3"
        );

        assertThat(result.model()).isEqualTo("qwen3-asr-flash");
        assertThat(result.transcript()).isEqualTo("北京今天天气怎么样");
        server.verify();
    }

    @Test
    void convertsM4aRecordingToWavBeforeRecognition(@TempDir Path tempDir) throws Exception {
        assumeTrue(ffmpegIsAvailable(), "本机未安装 FFmpeg，跳过 M4A 转换集成测试");
        byte[] m4a = createRegularM4aSample(tempDir);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SpeechRecognitionService service = new SpeechRecognitionService(
                builder.build(),
                "test-key",
                "https://example.test/chat/completions",
                "qwen3-asr-flash",
                "ffmpeg"
        );
        SpeechRecognitionService.PreparedAudio prepared = service.prepareFileForRecognition(m4a, "1.m4a");

        assertThat(prepared.mimeType()).isEqualTo("audio/wav");
        assertThat(new String(prepared.bytes(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(readLittleEndianInt(prepared.bytes(), 4)).isEqualTo(prepared.bytes().length - 8);
        assertThat(new String(prepared.bytes(), 36, 4, StandardCharsets.US_ASCII)).isEqualTo("data");
        assertThat(readLittleEndianInt(prepared.bytes(), 40)).isEqualTo(prepared.bytes().length - 44);

        server.expect(requestTo("https://example.test/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content[0].input_audio.data")
                        .value(org.hamcrest.Matchers.startsWith("data:audio/wav;base64,UklGR")))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"这是一段录音\"}}]}",
                        MediaType.APPLICATION_JSON
                ));

        SpeechRecognitionService.TranscriptionResult result = service.transcribeFile(m4a, "1.m4a");

        assertThat(result.transcript()).isEqualTo("这是一段录音");
        server.verify();
    }

    private static boolean ffmpegIsAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] createRegularM4aSample(Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("ordinary-recording.m4a");
        Process process = new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=8",
                "-c:a", "aac",
                "-b:a", "128k",
                outputFile.toString()
        ).start();
        process.getInputStream().readAllBytes();
        byte[] error = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        assertThat(exitCode)
                .withFailMessage("生成 M4A 测试录音失败：%s", new String(error, StandardCharsets.UTF_8))
                .isZero();
        byte[] output = Files.readAllBytes(outputFile);
        assertThat(output).hasSizeGreaterThan(65_536);
        return output;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
