package com.westart.ai.westart.wechat.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WechatMessageHandlerSearchPromptTest {

    @Test
    void newsPromptRequiresReadableVerifiedSections() {
        String prompt = WechatMessageHandler.buildWebSearchAnswerPrompt(
                "2026-07-20",
                "1. 示例新闻\n   这是搜索摘要",
                "今天有什么新闻"
        );

        assertThat(prompt)
                .contains("只能陈述搜索材料直接支持的事实")
                .contains("精选3至5条")
                .contains("✅ **简短标题**：事实说明")
                .contains("不写成拥挤的一整段")
                .contains("2026-07-20");
    }

    @Test
    void internalMarkersAreRemovedFromFinalAnswer() {
        String answer = WechatMessageHandler.cleanWebSearchAnswer(
                "[已联网搜索：2026年7月20日最新新闻] [语音回复] 当然可以！"
        );

        assertThat(answer).isEqualTo("当然可以！");
    }

    @Test
    void currentNewsAlwaysRequiresWebSearch() {
        assertThat(WechatCommandParser.requiresWebSearch("今天有什么新闻")).isTrue();
        assertThat(WechatCommandParser.requiresWebSearch("目前的国际热点有哪些")).isTrue();
        assertThat(WechatCommandParser.requiresWebSearch("现在的美国总统是谁")).isTrue();
        assertThat(WechatCommandParser.requiresWebSearch("你好")).isFalse();
        assertThat(WechatCommandParser.requiresWebSearch("杭州今天天气")).isFalse();
    }

    @Test
    void weatherFallbackPromptForbidsInventedRealtimeData() {
        String prompt = WechatMessageHandler.buildWeatherSearchAnswerPrompt(
                "2026-07-20",
                "杭州",
                "搜索结果：杭州 32℃"
        );

        assertThat(prompt)
                .contains("仅根据下面的联网搜索材料")
                .contains("不得编造")
                .contains("杭州")
                .contains("2026-07-20");
    }

    @Test
    void ttsFailureFallsBackToOriginalTextAndExplainsError() {
        String fallback = WechatMessageHandler.voiceTextFallback(
                "语音合成失败",
                new IllegalStateException("HTTP 503"),
                "今天天气真好"
        );

        assertThat(fallback)
                .contains("已自动改为文字回复")
                .contains("错误：HTTP 503")
                .endsWith("今天天气真好");
    }
}
