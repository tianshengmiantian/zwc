package com.westart.ai.westart.ai.orchestration;

import com.westart.ai.westart.ai.chat.BailianService.ChatMessage;
import com.westart.ai.westart.ai.chat.LangChain4jMessageMapper;
import com.westart.ai.westart.ai.config.AiModelConfiguration;
import com.westart.ai.westart.common.exception.ApiIntegrationException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets the text model choose whether a WeChat message should be answered
 * normally or handled by one of the application's real tools.
 */
@Service
public class AiActionPlannerService {

    public static final String QUERY_WEATHER = "query_weather";
    public static final String GENERATE_IMAGE = "generate_image";
    public static final String SPEAK_TEXT = "speak_text";
    public static final String SEARCH_WEB = "search_web";
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            QUERY_WEATHER,
            GENERATE_IMAGE,
            SPEAK_TEXT,
            SEARCH_WEB
    );
    private static final String SYSTEM_INSTRUCTION = """
            你是微信 AI 助手的回答与工具调度器，也是用户的朋友。
            用自然随意的聊天语气，像朋友发微信一样。不要用新闻播报腔、不要列清单式的 emoji 标题。
            根据你的知识直接回答用户问题，不要说你无法获取实时信息或建议用户查看其他来源。
            查询天气必须调用 query_weather；生成或绘制图片必须调用 generate_image；
            要求用语音回复时必须调用 speak_text。涉及今天新闻、最新消息、实时数据、当前人物或模型知识截止日期之后的信息时，必须调用 search_web，不能直接凭记忆回答。
            不要声称已经查询、画图或发送语音，除非你实际返回对应的工具调用。
            工具参数使用简洁、完整的中文。用户要求用语音播报天气时，query_weather 的 response_mode 必须为 voice。
            所有 speak_text 的 text 参数都必须是最终真正要朗读的内容，不能是尚未执行的操作指令。
            例如"用语音给我讲个笑话"应先创作实际笑话，再把笑话放入 text，不能把"给我讲个笑话"放入 text。
            speak_text 可以通过 voice 选择音色：默认或甜美女声用 Cherry，温柔女声用 Serena，男声或沉稳男声用 Ethan，
            二次元女声用 Chelsie，活泼女声用 Momo。用户未指定音色时不要填写 voice，使用系统默认音色。
            generate_image 的 prompt 必须是可以直接生成图片的最终、完整画面描述，不能只是"帮我画图"等操作指令。
            识别图片生成意图时应理解用户自然语言的语义，不要依赖固定说法；画、生成、创作、设计、做一张、给我看看某个画面等表达都可能要求生成图片。
            如果最近一次助手消息带有"[回复形式：图片]"和"[当前图片描述：...]"，用户继续要求增加、删除、替换元素或改变风格时，应调用 generate_image，并把旧描述与本次修改合并为完整的新 prompt。
            图片上下文标记只供内部理解，不能原样展示给用户。
            语音指令包含"我的名字""刚才""上面""再加上"等引用时，应结合对话历史解析。
            用户只说"用语音说"而没有指定内容时，应朗读最近一次助手回答，不要朗读"用语音说"本身。
            如果最近一次助手消息带有"[回复形式：语音]"标记，用户接着说"换一个""再来一个""继续""讲个别的"等延续要求时，默认继续调用 speak_text；除非用户明确要求改用文字。
            "[回复形式：语音]"只是内部上下文标记，不能把这个标记读出来或展示给用户。
            如果历史中没有用户所引用的信息，不要猜测，直接用文字询问用户，不调用工具。
            不要把历史中"我已使用某模型生成并发送了图片"等内部执行记录原样重复给用户。
            如果用户只发了无法独立理解的连接词，应简短询问补充内容，不要复述上一条助手消息。
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    @Autowired
    public AiActionPlannerService(
            @Qualifier(AiModelConfiguration.TEXT_CHAT_MODEL) ChatModel chatModel,
            @Value("${bailian.api-key}") String apiKey,
            @Value("${bailian.text-model}") String model
    ) {
        this(chatModel, new ObjectMapper(), apiKey, model);
    }

    AiActionPlannerService(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            String apiKey,
            String model
    ) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    public ActionPlan plan(List<ChatMessage> conversation) {
        requireApiKey();
        if (conversation == null || conversation.isEmpty()) {
            throw new ApiIntegrationException("Function Calling 对话内容不能为空");
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>(conversation.size() + 1);
        messages.add(SystemMessage.from(
                "当前日期：" + java.time.LocalDate.now() + "（星期"
                        + "日一二三四五六".charAt(java.time.LocalDate.now().getDayOfWeek().getValue() % 7)
                        + "）。\n" + SYSTEM_INSTRUCTION
        ));
        messages.addAll(LangChain4jMessageMapper.toLangChain4j(conversation));

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolDefinitions())
                .toolChoice(ToolChoice.AUTO)
                .build();

        try {
            return extractPlan(chatModel.chat(request));
        } catch (ApiIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiIntegrationException("百炼 Function Calling 请求失败：" + exception.getMessage(), exception);
        }
    }

    private ActionPlan extractPlan(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            throw new ApiIntegrationException("Function Calling 响应中缺少 message");
        }
        AiMessage message = response.aiMessage();
        String content = message.text() == null ? "" : message.text().trim();
        List<ActionCall> calls = message.toolExecutionRequests().stream()
                .map(this::parseToolCall)
                .toList();
        if (calls.isEmpty() && content.isBlank()) {
            throw new ApiIntegrationException("Function Calling 没有返回回答或工具调用");
        }
        String actualModel = response.modelName();
        return new ActionPlan(
                actualModel == null || actualModel.isBlank() ? model : actualModel,
                content,
                List.copyOf(calls)
        );
    }

    private ActionCall parseToolCall(ToolExecutionRequest request) {
        String id = textValue(request.id());
        String name = textValue(request.name());
        if (!ALLOWED_TOOLS.contains(name)) {
            throw new ApiIntegrationException("模型请求了未授权工具：" + name);
        }
        Map<String, Object> arguments = parseArguments(request.arguments());
        return new ActionCall(id, name, arguments);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return Map.copyOf(result);
        }
        if (!(value instanceof String json) || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException exception) {
            throw new ApiIntegrationException("模型返回的工具参数不是有效 JSON", exception);
        }
    }

    private static List<ToolSpecification> toolDefinitions() {
        return List.of(
                functionTool(
                        QUERY_WEATHER,
                        "查询指定地点的实时天气。用户要求语音播报天气时，把 response_mode 设为 voice。",
                        JsonObjectSchema.builder()
                                .addStringProperty("location", "城市、区县或地点名称")
                                .addEnumProperty("response_mode", List.of("text", "voice"), "回复形式，默认 text")
                                .required("location")
                                .additionalProperties(false)
                                .build()
                ),
                functionTool(
                        GENERATE_IMAGE,
                        "根据用户描述真正生成一张图片并发送到微信。",
                        oneRequiredString("prompt", "用于图片生成的完整视觉描述")
                ),
                functionTool(
                        SPEAK_TEXT,
                        "把最终回答合成为语音并回复用户；可以按用户要求选择音色。",
                        JsonObjectSchema.builder()
                                .addStringProperty("text", "要对用户说或朗读的最终自然语言内容")
                                .addEnumProperty(
                                        "voice",
                                        List.of("Cherry", "Serena", "Ethan", "Chelsie", "Momo"),
                                        "可选音色：Cherry甜美女声、Serena温柔女声、Ethan沉稳男声、Chelsie二次元女声、Momo活泼女声"
                                )
                                .required("text")
                                .additionalProperties(false)
                                .build()
                ),
                functionTool(
                        SEARCH_WEB,
                        "联网搜索最新信息。用于查询新闻、实时数据、百科知识等模型知识截止日期之后的内容。",
                        oneRequiredString("query", "搜索关键词或问题")
                )
        );
    }

    private static JsonObjectSchema oneRequiredString(String name, String description) {
        return JsonObjectSchema.builder()
                .addStringProperty(name, description)
                .required(name)
                .additionalProperties(false)
                .build();
    }

    private static ToolSpecification functionTool(
            String name,
            String description,
            JsonObjectSchema parameters
    ) {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiIntegrationException("未配置环境变量 DASHSCOPE_API_KEY");
        }
    }

    private static String textValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record ActionPlan(String model, String content, List<ActionCall> calls) {
        public boolean hasActions() {
            return calls != null && !calls.isEmpty();
        }
    }

    public record ActionCall(String id, String name, Map<String, Object> arguments) {
        public String stringArgument(String name) {
            Object value = arguments == null ? null : arguments.get(name);
            return value == null ? "" : value.toString().trim();
        }
    }
}
