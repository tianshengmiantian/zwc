package com.westart.ai.westart.tool.speech.client;

import com.westart.ai.westart.common.exception.ApiIntegrationException;
import com.westart.ai.westart.tool.speech.SpeechRecognitionService;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Minimal adapter for Qwen3-TTS-Realtime's WebSocket protocol.
 * LangChain4j chooses the tool arguments; this class only performs audio transport.
 */
public final class QwenRealtimeTtsClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final long RESPONSE_TIMEOUT_SECONDS = 90;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QwenRealtimeTtsClient() {
        this(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new ObjectMapper()
        );
    }

    QwenRealtimeTtsClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public byte[] synthesize(
            String apiKey,
            String baseUrl,
            String model,
            String voice,
            String text,
            int sampleRate
    ) {
        URI endpoint = realtimeEndpoint(baseUrl, model);
        RealtimeListener listener = new RealtimeListener(objectMapper, voice, text, sampleRate);
        WebSocket webSocket = null;
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(endpoint, listener)
                    .get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            byte[] pcm = listener.audioFuture()
                    .get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (pcm.length == 0) {
                throw new ApiIntegrationException("百炼实时语音合成没有返回音频数据");
            }
            return SpeechRecognitionService.wrapPcmAsWav(pcm, sampleRate, 16, 1);
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼实时语音合成失败：" + rootMessage(exception), exception);
        } finally {
            if (webSocket != null && !listener.audioFuture().isDone()) {
                webSocket.abort();
            }
        }
    }

    static URI realtimeEndpoint(String baseUrl, String model) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (!value.startsWith("wss://")) {
            throw new ApiIntegrationException("实时 TTS 地址必须使用 wss:// 协议");
        }
        if (value.contains("model=")) {
            return URI.create(value);
        }
        String separator = value.contains("?") ? "&" : "?";
        return URI.create(value + separator + "model="
                + URLEncoder.encode(model, StandardCharsets.UTF_8));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final class RealtimeListener implements WebSocket.Listener {

        private final ObjectMapper objectMapper;
        private final String voice;
        private final String text;
        private final int sampleRate;
        private final StringBuilder messageBuffer = new StringBuilder();
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> audioFuture = new CompletableFuture<>();

        private RealtimeListener(ObjectMapper objectMapper, String voice, String text, int sampleRate) {
            this.objectMapper = objectMapper;
            this.voice = voice;
            this.text = text;
            this.sampleRate = sampleRate;
        }

        CompletableFuture<byte[]> audioFuture() {
            return audioFuture;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                handleEvent(webSocket, message);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            audioFuture.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!audioFuture.isDone()) {
                audioFuture.completeExceptionally(new IllegalStateException(
                        "WebSocket 提前关闭，状态码 " + statusCode + "：" + reason
                ));
            }
            return CompletableFuture.completedFuture(null);
        }

        @SuppressWarnings("unchecked")
        private void handleEvent(WebSocket webSocket, String json) {
            try {
                Map<String, Object> event = objectMapper.readValue(json, Map.class);
                String type = String.valueOf(event.getOrDefault("type", ""));
                switch (type) {
                    case "session.created" -> send(webSocket, Map.of(
                            "event_id", eventId(),
                            "type", "session.update",
                            "session", Map.of(
                                    "voice", voice,
                                    "mode", "commit",
                                    "language_type", "Chinese",
                                    "response_format", "pcm",
                                    "sample_rate", sampleRate
                            )
                    )).whenComplete((ignored, error) -> completeOnSendError(error));
                    case "session.updated" -> {
                        send(webSocket, Map.of(
                                "event_id", eventId(),
                                "type", "input_text_buffer.append",
                                "text", text
                        )).thenCompose(ignored -> send(webSocket, Map.of(
                                        "event_id", eventId(),
                                        "type", "input_text_buffer.commit"
                                )))
                                .whenComplete((ignored, error) -> completeOnSendError(error));
                    }
                    case "response.audio.delta" -> appendAudio(event.get("delta"));
                    case "response.done" -> send(webSocket, Map.of(
                            "event_id", eventId(),
                            "type", "session.finish"
                    )).whenComplete((ignored, error) -> completeOnSendError(error));
                    case "session.finished" -> audioFuture.complete(pcm.toByteArray());
                    case "error" -> audioFuture.completeExceptionally(
                            new ApiIntegrationException("百炼实时 TTS 返回错误：" + errorMessage(event))
                    );
                    default -> {
                        // Other lifecycle events do not require client action.
                    }
                }
            } catch (Exception exception) {
                audioFuture.completeExceptionally(exception);
                webSocket.abort();
            }
        }

        private void appendAudio(Object value) {
            if (!(value instanceof String encoded) || encoded.isBlank()) {
                return;
            }
            byte[] chunk = Base64.getDecoder().decode(encoded);
            pcm.writeBytes(chunk);
        }

        private CompletableFuture<WebSocket> send(WebSocket webSocket, Map<String, Object> event) {
            String json = objectMapper.writeValueAsString(event);
            return webSocket.sendText(json, true);
        }

        private void completeOnSendError(Throwable error) {
            if (error != null) {
                audioFuture.completeExceptionally(error);
            }
        }

        private static String errorMessage(Map<String, Object> event) {
            Object error = event.get("error");
            if (error instanceof Map<?, ?> details) {
                Object message = details.get("message");
                if (message != null) {
                    return String.valueOf(message);
                }
            }
            return String.valueOf(error);
        }

        private static String eventId() {
            return "event_" + UUID.randomUUID().toString().replace("-", "");
        }
    }
}
