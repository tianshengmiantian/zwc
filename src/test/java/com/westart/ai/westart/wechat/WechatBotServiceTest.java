package com.westart.ai.westart.wechat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WechatBotServiceTest {

    @Test
    void extractsLocationFromWeatherQuestion() {
        assertThat(WechatBotService.extractWeatherLocation("请帮我查一下今天上海的天气怎么样？", "北京"))
                .isEqualTo("上海");
    }

    @Test
    void usesDefaultLocationWhenQuestionHasNoLocation() {
        assertThat(WechatBotService.extractWeatherLocation("今天天气怎么样？", "杭州"))
                .isEqualTo("杭州");
    }

    @Test
    void recognizesTemperatureQuestionAsWeatherIntent() {
        assertThat(WechatBotService.containsWeatherIntent("广州现在温度多少"))
                .isTrue();
    }
}
