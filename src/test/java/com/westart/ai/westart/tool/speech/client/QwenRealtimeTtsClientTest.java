package com.westart.ai.westart.tool.speech.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QwenRealtimeTtsClientTest {

    @Test
    void addsRealtimeModelToWebSocketEndpoint() {
        assertThat(QwenRealtimeTtsClient.realtimeEndpoint(
                "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
                "qwen3-tts-flash-realtime"
        ).toString()).isEqualTo(
                "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-tts-flash-realtime"
        );
    }
}
