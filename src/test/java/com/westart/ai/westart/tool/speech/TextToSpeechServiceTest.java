package com.westart.ai.westart.tool.speech;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TextToSpeechServiceTest {

    @Test
    void synthesizesAndDownloadsMp3() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TextToSpeechService service = new TextToSpeechService(
                builder.build(),
                "test-key",
                "https://dashscope.example.test/tts",
                "qwen-audio-3.0-tts-flash",
                "longanlingxi",
                24_000
        );
        byte[] mp3 = new byte[]{'I', 'D', '3', 4, 0, 0, 1, 2, 3};

        server.expect(once(), requestTo("https://dashscope.example.test/tts"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen-audio-3.0-tts-flash"))
                .andExpect(jsonPath("$.input.text").value("你好，欢迎回来。"))
                .andExpect(jsonPath("$.input.voice").value("longanlingxi"))
                .andExpect(jsonPath("$.input.format").value("mp3"))
                .andExpect(jsonPath("$.input.sample_rate").value(24000))
                .andRespond(withSuccess("""
                        {
                          "output": {
                            "audio": {
                              "url": "https://audio.example.test/reply.mp3"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://audio.example.test/reply.mp3"))
                .andExpect(method(GET))
                .andRespond(withSuccess(mp3, MediaType.valueOf("audio/mpeg")));

        TextToSpeechService.SynthesizedSpeech speech = service.synthesize("你好，欢迎回来。");

        assertThat(speech.model()).isEqualTo("qwen-audio-3.0-tts-flash");
        assertThat(speech.voice()).isEqualTo("longanlingxi");
        assertThat(speech.audioBytes()).isEqualTo(mp3);
        assertThat(speech.fileExtension()).isEqualTo("mp3");
        assertThat(speech.fileName()).isEqualTo("bailian-voice.mp3");
        server.verify();
    }

    @Test
    void usesQwen3TtsPayloadAndAcceptsWav() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TextToSpeechService service = new TextToSpeechService(
                builder.build(),
                "test-key",
                "https://dashscope.example.test/generation",
                "qwen3-tts-flash",
                "Cherry",
                24_000
        );
        byte[] wav = new byte[]{'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2};

        server.expect(once(), requestTo("https://dashscope.example.test/generation"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.model").value("qwen3-tts-flash"))
                .andExpect(jsonPath("$.input.text").value("你好"))
                .andExpect(jsonPath("$.input.voice").value("Cherry"))
                .andExpect(jsonPath("$.input.language_type").value("Chinese"))
                .andRespond(withSuccess("""
                        {
                          "output": {
                            "audio": {
                              "url": "https://audio.example.test/reply.wav"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://audio.example.test/reply.wav"))
                .andExpect(method(GET))
                .andRespond(withSuccess(wav, MediaType.valueOf("audio/wav")));

        TextToSpeechService.SynthesizedSpeech speech = service.synthesize("你好");

        assertThat(speech.audioBytes()).isEqualTo(wav);
        assertThat(speech.fileExtension()).isEqualTo("wav");
        assertThat(speech.fileName()).isEqualTo("bailian-voice.wav");
        server.verify();
    }

    @Test
    void removesMarkdownBeforeSpeechSynthesis() {
        assertThat(TextToSpeechService.normalizeSpeechText("## 标题\n请看 **重点** 和 [文档](https://example.com)。"))
                .isEqualTo("标题 请看 重点 和 文档。");
    }

    @Test
    void resolvesNaturalVoiceNames() {
        assertThat(TextToSpeechService.resolveVoice("男声", "Cherry")).isEqualTo("Ethan");
        assertThat(TextToSpeechService.resolveVoice("温柔女声", "Cherry")).isEqualTo("Serena");
        assertThat(TextToSpeechService.resolveVoice("Momo", "Cherry")).isEqualTo("Momo");
        assertThat(TextToSpeechService.resolveVoice(null, "Cherry")).isEqualTo("Cherry");
    }
}
