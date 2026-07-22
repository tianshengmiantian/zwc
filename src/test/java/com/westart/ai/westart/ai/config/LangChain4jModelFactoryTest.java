package com.westart.ai.westart.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jModelFactoryTest {

    @Test
    void convertsChatCompletionUrlToOpenAiBaseUrl() {
        assertThat(LangChain4jModelFactory.normalizeBaseUrl(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        )).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
    }
}
