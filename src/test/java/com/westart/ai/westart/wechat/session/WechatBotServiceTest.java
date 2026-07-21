package com.westart.ai.westart.wechat.session;

import com.westart.ai.westart.wechat.message.WechatCommandParser;
import com.westart.ai.westart.wechat.media.WechatMediaSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WechatBotServiceTest {

    @Test
    void detectsAndExtractsImageGenerationInstruction() {
        assertThat(List.of(
                "请帮我画一只坐在窗边的橘猫",
                "画拉布拉多小狗",
                "生成一个 可爱小猫图片",
                "帮我生成一张海边日落图",
                "给我来张宇航员小狗图片",
                "我想要一张山水画图片",
                "绘制一幅未来城市",
                "制作一张咖啡店海报",
                "设计一个猫咪头像",
                "生成90年代家庭聚餐的场景",
                "创建一个未来城市画面",
                "文生图：水墨风格的西湖",
                "AI画图：一只穿宇航服的熊猫",
                "把一只橘猫画出来",
                "根据春天的西湖生成一张图",
                "给我一张手机壁纸"
        )).allMatch(WechatCommandParser::isImageGenerationIntent);
        assertThat(WechatCommandParser.extractImagePrompt("请帮我画一只坐在窗边的橘猫"))
                .isEqualTo("坐在窗边的橘猫");
        assertThat(WechatCommandParser.extractImagePrompt("画拉布拉多小狗"))
                .isEqualTo("拉布拉多小狗");
        assertThat(WechatCommandParser.extractImagePrompt("生成90年代家庭聚餐的场景"))
                .isEqualTo("90年代家庭聚餐的场景");
        assertThat(WechatCommandParser.extractImagePrompt("生成一个 可爱小猫图片"))
                .isEqualTo("可爱小猫");
        assertThat(WechatCommandParser.extractImagePrompt("把一只橘猫画出来"))
                .isEqualTo("一只橘猫");
        assertThat(WechatCommandParser.extractImagePrompt("根据春天的西湖生成一张图"))
                .isEqualTo("春天的西湖");
        assertThat(List.of(
                "请分析我发来的这张图片",
                "怎么生成图片？",
                "如何写出好的绘图提示词？",
                "帮我生成一篇文章",
                "设计一个数据库表"
        )).noneMatch(WechatCommandParser::isImageGenerationIntent);
    }

    @Test
    void extractsLocationFromWeatherQuestion() {
        assertThat(WechatCommandParser.extractWeatherLocation("请帮我查一下今天上海的天气怎么样？", "北京"))
                .isEqualTo("上海");
    }

    @Test
    void usesDefaultLocationWhenQuestionHasNoLocation() {
        assertThat(WechatCommandParser.extractWeatherLocation("今天天气怎么样？", "杭州"))
                .isEqualTo("杭州");
    }

    @Test
    void recognizesTemperatureQuestionAsWeatherIntent() {
        assertThat(WechatCommandParser.containsWeatherIntent("广州现在温度多少"))
                .isTrue();
    }

    @Test
    void recognizesAndExtractsVoiceReplyCommands() {
        assertThat(List.of(
                "你能对我说句话吗？",
                "请用语音说欢迎回来",
                "把这句话朗读出来",
                "用声音告诉我答案",
                "你能给我发个语音吗？",
                "你给我发个语音：今天天气真好",
                "请发送一条语音说欢迎回来",
                "语音说你好",
                "用语音发你好"
        )).allMatch(WechatCommandParser::isVoiceReplyIntent);
        assertThat(WechatCommandParser.extractVoiceReplyText("请用语音说欢迎回来"))
                .isEqualTo("欢迎回来");
        assertThat(WechatCommandParser.extractVoiceReplyText("你能对我说句话吗？"))
                .isEqualTo("你好，很高兴和你聊天。");
        assertThat(WechatCommandParser.extractVoiceReplyText("你给我发个语音：今天天气真好"))
                .isEqualTo("今天天气真好");
        assertThat(WechatCommandParser.extractVoiceReplyText("请发送一条语音说欢迎回来"))
                .isEqualTo("欢迎回来");
        assertThat(WechatCommandParser.extractVoiceReplyText("语音说你好"))
                .isEqualTo("你好");
        assertThat(WechatCommandParser.extractVoiceReplyText("用语音发你好"))
                .isEqualTo("你好");
    }

    @Test
    void separatesVoiceCapabilityQuestionsFromVoiceActions() {
        assertThat(List.of(
                "可不可以发语音",
                "你能发语音吗？",
                "支持语音吗"
        )).allMatch(WechatCommandParser::isVoiceCapabilityQuestion);
        assertThat(List.of(
                "可不可以发语音",
                "你能发语音吗？",
                "支持语音吗"
        )).noneMatch(WechatCommandParser::isVoiceReplyIntent);
        assertThat(WechatCommandParser.isVoiceReplyIntent("你能给我发个语音吗？")).isTrue();
        assertThat(WechatCommandParser.isVoiceReplyIntent("用语音说你好")).isTrue();
    }

    @Test
    void detectsVoiceInstructionsThatNeedConversationContext() {
        assertThat(WechatCommandParser.requiresContextualVoiceComposition(
                "用语音说你好\n并且加上我的名字"
        )).isTrue();
        assertThat(WechatCommandParser.requiresContextualVoiceComposition("用语音说")).isTrue();
        assertThat(WechatCommandParser.requiresContextualVoiceComposition("用语音给我讲个笑话")).isTrue();
        assertThat(WechatCommandParser.requiresContextualVoiceComposition("用语音说给我讲个笑话")).isTrue();
        assertThat(WechatCommandParser.requiresContextualVoiceComposition(
                "用语音给我讲个关于动物的笑话")).isTrue();
        assertThat(WechatCommandParser.requiresContextualVoiceComposition(
                "用语音讲一个适合小朋友听的故事")).isTrue();
        assertThat(WechatCommandParser.requiresContextualVoiceComposition("用语音说你好")).isFalse();
        assertThat(WechatCommandParser.isUnresolvedVoiceContentRequest("给我讲个笑话")).isTrue();
        assertThat(WechatCommandParser.isUnresolvedVoiceContentRequest(
                "给我讲个关于动物的笑话")).isTrue();
        assertThat(WechatCommandParser.isUnresolvedVoiceContentRequest(
                "讲一个程序员冷笑话")).isTrue();
        assertThat(WechatCommandParser.isUnresolvedVoiceContentRequest(
                "有一天，一只企鹅走进了咖啡店。"
        )).isFalse();
    }

    @Test
    void combinesBufferedImageGenerationText() {
        assertThat(WechatCommandParser.isImageGenerationIntent(
                "生成海边美景图\n并且\n加上一些人"
        )).isTrue();
        assertThat(WechatCommandParser.extractImagePrompt(
                "生成海边美景图\n并且\n加上一些人"
        )).contains("海边").contains("加上一些人");
        assertThat(List.of(
                "帮我生成",
                "请帮我画",
                "生成一张",
                "绘制一幅"
        )).allMatch(WechatCommandParser::isPotentialGenerationPrefix);
    }

    @Test
    void detectsIncompleteDownloadedFileByDeclaredSize() {
        assertThat(WechatMediaSupport.mediaIntegrityIssue(new byte[8], 10, ""))
                .contains("消息标注 10 字节")
                .contains("实际下载 8 字节");
    }

    @Test
    void acceptsDownloadedFileWhenSizeAndMd5Match() {
        byte[] bytes = "complete audio".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(WechatMediaSupport.mediaIntegrityIssue(
                bytes,
                bytes.length,
                "658dfa1d86ec068c404821012faa9f07"
        )).isEmpty();
    }
}
