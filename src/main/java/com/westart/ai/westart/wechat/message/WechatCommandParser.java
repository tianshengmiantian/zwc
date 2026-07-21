package com.westart.ai.westart.wechat.message;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text parsing rules used as a reliable fallback when Function Calling
 * does not return a tool request.
 */
public final class WechatCommandParser {

    private static final String IMAGE_NOUNS =
            "图片|图像|图|插画|海报|头像|壁纸|封面|logo|LOGO|标志|表情包|宣传图";
    private static final String VISUAL_RESULT_NOUNS =
            "场景|画面|风景|肖像|人像|漫画|卡通|艺术照|摄影作品|艺术作品";
    private static final Pattern IMAGE_HELP_QUESTION = Pattern.compile(
            "^(怎么|怎样|如何|为什么|什么是|介绍|解释|教程|哪里可以).*(生成|画|绘制|制作|设计).*(图片|图像|图|插画)"
    );
    private static final Pattern IMAGE_PREFIX_COMMAND = Pattern.compile(
            "^(文生图|图片生成|AI画图|ai画图|智能画图)\\s*[：:,，]?\\s*(.*)$"
    );
    private static final Pattern IMAGE_BASED_ON_COMMAND = Pattern.compile(
            "^(?:请|麻烦)?\\s*(?:根据|按照)\\s*(.+?)\\s*"
                    + "(?:生成|创建|制作|绘制|画|设计)\\s*"
                    + "(?:一张|一幅|一个|一只|张|幅|个|只)?\\s*(?:" + IMAGE_NOUNS + ")?[。！!]?$"
    );
    private static final Pattern IMAGE_DRAW_OUT_COMMAND = Pattern.compile(
            "^(?:请|麻烦)?\\s*(?:把)?\\s*(.+?)\\s*(?:画|绘制|绘画)出来[。！!]?$"
    );
    private static final Pattern IMAGE_DIRECT_ACTION = Pattern.compile(
            "^(?:(?:我想(?:要|让你)?|我要|请|麻烦|可以|能不能|能否)\\s*)?"
                    + "(?:你\\s*)?(?:(?:帮我|给我|为我|替我)\\s*)?(?:(?:用AI|用ai|让AI|让ai)\\s*)?"
                    + "(画|绘制|绘画|生成|创建|制作|设计|做|弄)\\s*"
                    + "(?:一张|一幅|一个|一只|张|幅|个|只)?\\s*(.*?)[。！!]?$"
    );
    private static final Pattern IMAGE_RESULT_REQUEST = Pattern.compile(
            "^(?:我想要|我想看|我要|请给我|给我来|给我|帮我来|来)\\s*"
                    + "(?:一张|一幅|一个|一只|张|幅|个|只)?\\s*(.*?)[。！!]?$"
    );

    private WechatCommandParser() {
    }

    public static boolean isVoiceReplyIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (isVoiceCapabilityQuestion(text)) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        return normalized.matches(".*(?:用语音|语音回复|语音说|语音发|语音发送|语音播报|用声音|朗读|读出来|念出来|说句话|对我说).*")
                || normalized.matches(".*(?:发|发送|来)(?:一?个|一?条)?语音.*");
    }

    public static boolean isVoiceCapabilityQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        if (!normalized.contains("语音")) {
            return false;
        }
        // “给我发个语音” is an action request, while “能发语音吗” only asks
        // whether the feature exists. A sentence containing the words to speak
        // is also always treated as an action.
        if (normalized.matches(".*(?:给我|帮我|为我).*(?:发|发送|回复|使用)?(?:一?个|一?条)?语音.*")
                || normalized.matches(".*语音(?:说|播报|朗读|回复)[：:,，]?.+")) {
            return false;
        }
        return normalized.matches(".*(?:可不可以|能不能|能否|是否能|会不会).*(?:发|发送|回复|使用)?语音(?:吗|呢|[？?])?.*")
                || normalized.matches(".*(?:可以|能|会)(?:发|发送|回复|使用)?语音(?:吗|[？?]).*")
                || normalized.matches(".*(?:是否)?支持语音(?:吗|呢|[？?])?.*");
    }

    public static String extractVoiceReplyText(String instruction) {
        String text = trim(instruction)
                .replaceAll("\\s+", "")
                .replaceFirst("^(?:请|麻烦)?(?:你)?(?:能不能|能|可以)?(?:给我|帮我|为我)?(?:发|发送|来)(?:一?个|一?条)?语音(?:吗)?", "")
                .replaceFirst("^(?:请|麻烦|能不能|可以)?(?:你)?(?:用语音|用声音|语音回复|语音说|语音发|语音发送|语音播报)(?:来)?(?:说|发|发送|告诉我|回复)?", "")
                .replaceFirst("^(?:请|麻烦)?(?:朗读|读出来|念出来|读一下|念一下)", "")
                .replaceFirst("^(?:你)?(?:能不能|能|可以)?(?:对我)?说句话(?:吗)?[？?]?$", "")
                .replaceFirst("^[：:,，\\s]+", "")
                .replaceFirst("^(?:说|读|朗读|念)(?:一下)?[：:,，\\s]*", "")
                .replaceFirst("[。！？!?]+$", "")
                .trim();
        return text.isBlank() ? "你好，很高兴和你聊天。" : text;
    }

    public static boolean requiresContextualVoiceComposition(String text) {
        if (!isVoiceReplyIntent(text)) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        return isBareVoiceInstruction(normalized)
                || isUnresolvedVoiceContentRequest(extractVoiceReplyText(text))
                || normalized.matches(".*(?:我的名字|我的姓名|我的昵称|刚才|上面|之前|前面|这个|那个|它).*" )
                || normalized.matches(".*(?:并且|另外|还有|再|加上|结合|根据|改成|换成).*" );
    }

    public static boolean isUnresolvedVoiceContentRequest(String text) {
        String normalized = trim(text).replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            return true;
        }
        // The requested content may contain qualifiers between the action and
        // the content type, for example “讲个关于动物的笑话” or
        // “说一段适合小朋友听的故事”. These are tasks to complete first,
        // not literal sentences to hand directly to TTS.
        return normalized.matches("^(?:(?:请|麻烦)?(?:你)?(?:给我|帮我|为我)?)?(?:讲|说|来).*(?:笑话|故事|段子|新闻|绕口令).*")
                || normalized.matches("^(?:(?:请|麻烦)?(?:你)?(?:给我|帮我|为我)?)?(?:回答|解释|介绍|总结|分析|评价|翻译|告诉我).*")
                || normalized.matches("^(?:给我|帮我)?(?:讲|说).*(?:听|一下)$");
    }

    private static boolean isBareVoiceInstruction(String normalized) {
        return normalized.matches("^(?:请|麻烦)?(?:你)?(?:用语音|用声音|语音回复|语音说|语音发|语音发送|语音播报)(?:来)?(?:说|发|发送|告诉我|回复)?[：:,，。！？!?]*$")
                || normalized.matches("^(?:请|麻烦)?(?:朗读|读出来|念出来|读一下|念一下)[：:,，。！？!?]*$");
    }

    public static boolean isPotentialGenerationPrefix(String text) {
        String normalized = trim(text).replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.matches("^(?:(?:请|麻烦|我想|我要|能不能|可以)?(?:你)?(?:帮我|给我|为我)?)?(?:生成|创建|制作|绘制|画|设计)(?:一张|一幅|一个|一只)?[：:,，]?$" );
    }

    public static boolean isImageGenerationIntent(String text) {
        return parseImageInstruction(text) != null;
    }

    public static String extractImagePrompt(String instruction) {
        ImageInstruction parsed = parseImageInstruction(instruction);
        return parsed == null ? "" : parsed.prompt();
    }

    public static boolean containsWeatherIntent(String text) {
        return text != null && (text.contains("天气") || text.contains("气温") || text.contains("温度"));
    }

    public static boolean requiresWebSearch(String text) {
        if (text == null || text.isBlank() || containsWeatherIntent(text)) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.matches(".*(?:新闻|今日要闻|头条|热搜|热点).*")) {
            return true;
        }
        if (normalized.contains("现任")) {
            return true;
        }
        return normalized.matches(
                ".*(?:今天|今日|刚刚|目前|当前|现在|实时|最新|近期|最近).*"
                        + "(?:消息|动态|进展|结果|数据|价格|股价|汇率|排名|赛程|比赛|政策|法规|发布|上线|更新|是谁|多少|怎么样).*"
        );
    }

    public static String extractWeatherLocation(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String location = text
                .replaceAll("[，。！？?、,!.\\s]", "")
                .replace("今天", "")
                .replace("现在", "")
                .replace("当前", "")
                .replace("实时", "")
                .replace("天气", "")
                .replace("气温", "")
                .replace("温度", "")
                .replace("帮我", "")
                .replace("麻烦", "")
                .replace("请", "")
                .replace("查询", "")
                .replace("查一下", "")
                .replace("查", "")
                .replace("看看", "")
                .replace("告诉我", "")
                .replace("怎么样", "")
                .replace("如何", "")
                .replace("情况", "")
                .replace("预报", "")
                .replace("一下", "")
                .replace("的", "")
                .trim();
        return location.isBlank() ? fallback : location;
    }

    private static ImageInstruction parseImageInstruction(String text) {
        String command = trim(text).replaceAll("\\s+", " ");
        if (command.isBlank() || IMAGE_HELP_QUESTION.matcher(command).matches()) {
            return null;
        }

        Matcher prefix = IMAGE_PREFIX_COMMAND.matcher(command);
        if (prefix.matches()) {
            return new ImageInstruction(cleanImagePrompt(prefix.group(2)));
        }

        Matcher basedOn = IMAGE_BASED_ON_COMMAND.matcher(command);
        if (basedOn.matches()) {
            return new ImageInstruction(cleanImagePrompt(basedOn.group(1)));
        }

        Matcher drawOut = IMAGE_DRAW_OUT_COMMAND.matcher(command);
        if (drawOut.matches()) {
            return new ImageInstruction(cleanImagePrompt(drawOut.group(1)));
        }

        Matcher direct = IMAGE_DIRECT_ACTION.matcher(command);
        if (direct.matches()) {
            String action = direct.group(1);
            boolean drawingAction = action.equals("画") || action.equals("绘制") || action.equals("绘画");
            if (drawingAction || containsImageNoun(command) || containsVisualResultNoun(command)) {
                return new ImageInstruction(cleanImagePrompt(direct.group(2)));
            }
        }

        Matcher resultRequest = IMAGE_RESULT_REQUEST.matcher(command);
        if (resultRequest.matches() && containsImageNoun(command)) {
            return new ImageInstruction(cleanImagePrompt(resultRequest.group(1)));
        }
        return null;
    }

    private static boolean containsImageNoun(String text) {
        return text.matches(".*(?:" + IMAGE_NOUNS + ").*");
    }

    private static boolean containsVisualResultNoun(String text) {
        return text.matches(".*(?:" + VISUAL_RESULT_NOUNS + ").*");
    }

    private static String cleanImagePrompt(String value) {
        return trim(value)
                .replaceFirst("^[：:,，\\s]+", "")
                .replaceFirst("^(图片|图像|照片)\\s*[：:,，]\\s*", "")
                .replaceFirst("(的)?(图片|图像|照片)[。！？!?]?$", "")
                .replaceFirst("[。！？!?]+$", "")
                .trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record ImageInstruction(String prompt) {
    }
}
