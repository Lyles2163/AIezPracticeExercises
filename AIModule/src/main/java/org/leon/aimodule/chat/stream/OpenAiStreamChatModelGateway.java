package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.leon.aimodule.tool.ChatTool;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DeepSeek 的 OpenAI 兼容端点。之所以自己读上游：Spring AI 1.0.0-M7 的
 * ChatCompletionMessage 没有 reasoning 字段，delta.reasoning_content 在 JSON
 * 绑定阶段就被丢掉（1.0.9 GA 同样没有，只有 1.1.x 有，而那要求整项目升框架）。
 *
 * 零新增依赖：WebClient 与 ServerSentEvent 解码器来自 classpath 上已有的 spring-webflux；
 * 没有 reactor-netty 时 Spring 6.1 把连接器回落到 JDK HttpClient —— 这条回落由
 * OpenAiStreamChatModelGatewayTest 的真实 HTTP 请求验证过，不是推测。
 */
@Component
public class OpenAiStreamChatModelGateway implements ChatModelGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ChatProperties props;
    private final ObjectMapper requestMapper;
    private final WebClient webClient;
    private volatile String testBaseUrl;

    public OpenAiStreamChatModelGateway(ChatProperties props, ObjectMapper mapper) {
        this.props = props;
        this.requestMapper = mapper;
        // 非 2xx 分支要把错误体读进内存才知道 status 之外的信息，所以放开缓冲上限。
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
        this.webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .exchangeStrategies(strategies)
                .build();
    }

    /** 测试把请求打到本机假上游；生产不调。 */
    void setBaseUrlForTest(String url) {
        this.testBaseUrl = url;
    }

    @Override
    public Flux<ChatModelGateway.ChatChunk> stream(List<CtxMessage> messages, ModelParams params,
                                                     String systemPromptOverride, List<ChatTool> tools) {
        String base = testBaseUrl == null ? props.getBaseUrl() : testBaseUrl;
        return execute(messages, params, systemPromptOverride, tools,
                base, "/v1/chat/completions", props.getApiKey(), params.modelKey());
    }

    /**
     * 用户自建供应商的一轮：baseUrl 已归一化到 /v1 结尾（见 BaseUrlGuard），
     * key 与上游模型名都来自本轮解析出的 route，绝不回落到服务端配置。
     */
    @Override
    public Flux<ChatModelGateway.ChatChunk> stream(List<CtxMessage> messages, ModelParams params,
                                                     String systemPromptOverride, List<ChatTool> tools,
                                                     ProviderRoute route) {
        if (route == null) {
            return stream(messages, params, systemPromptOverride, tools);
        }
        String base = testBaseUrl == null ? route.baseUrl() : testBaseUrl;
        return execute(messages, params, systemPromptOverride, route.supportsTools() ? tools : null,
                base, "/chat/completions", route.apiKey(), route.upstreamModel());
    }

    private Flux<ChatModelGateway.ChatChunk> execute(List<CtxMessage> messages, ModelParams params,
                                                     String systemPromptOverride, List<ChatTool> tools,
                                                     String baseUrl, String path, String apiKey,
                                                     String wireModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", wireModel);
        body.put("messages", toWireMessages(messages, systemPromptOverride));
        body.put("stream", true);
        // 不发这一行就没有 usage 帧 —— 上一期是靠 spring.ai.openai...stream-usage 让库代发。
        body.put("stream_options", Map.of("include_usage", true));
        if (params.maxTokens() != null) {
            body.put("max_tokens", params.maxTokens());
        }
        if (params.temperature() != null) {
            body.put("temperature", params.temperature());
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", toWireTools(tools));
            body.put("tool_choice", "auto");
        }
        String json = writeRequestJson(body);
        return webClient.mutate().baseUrl(baseUrl).build()
                .post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchangeToFlux(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                        });
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMapMany(text -> Flux.error(new UpstreamStatusException(
                                    resp.statusCode().value(),
                                    resp.statusCode().value() + " from POST " + path + ": "
                                            + truncate(text))));
                })
                // 心跳只保活"我们→前端"，保不了"上游→我们"这一跳，所以这里必须有空闲判定。
                .timeout(Duration.ofSeconds(props.getUpstreamIdleTimeoutSeconds()))
                .mapNotNull(sse -> toChunk(sse.data()));
    }

    /** system 提示放第一位，与上一期 ChatClient.system() 的线上形状一致。 */
    private List<Map<String, Object>> toWireMessages(List<CtxMessage> messages, String systemPromptOverride) {
        List<Map<String, Object>> out = new ArrayList<>();
        String system = systemPromptOverride != null ? systemPromptOverride : props.getSystemPrompt();
        if (system != null && !system.isBlank()) {
            out.add(Map.of("role", "system", "content", system));
        }
        for (CtxMessage m : messages) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("role", m.role());
            if ("tool".equals(m.role())) {
                // 工具结果消息必须带 tool_call_id
                wire.put("tool_call_id", m.toolCallId() == null ? "" : m.toolCallId());
                wire.put("content", m.content() == null ? "" : m.content());
            } else if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                // assistant 发起工具调用：content 可为空字符串，tool_calls 数组必填
                wire.put("content", m.content() == null ? "" : m.content());
                List<Map<String, Object>> calls = new ArrayList<>(m.toolCalls().size());
                for (CtxMessage.ToolCallRef tc : m.toolCalls()) {
                    calls.add(Map.of(
                            "id", tc.id() == null ? "" : tc.id(),
                            "type", "function",
                            "function", Map.of(
                                    "name", tc.name() == null ? "" : tc.name(),
                                    "arguments", tc.arguments() == null ? "" : tc.arguments())));
                }
                wire.put("tool_calls", calls);
            } else {
                wire.put("content", m.content() == null ? "" : m.content());
            }
            out.add(wire);
        }
        return out;
    }

    /** 把内部 ChatTool 列表转成 OpenAI tools 协议的数组。 */
    private List<Map<String, Object>> toWireTools(List<ChatTool> tools) {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (ChatTool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            try {
                fn.put("parameters", MAPPER.readTree(tool.parametersSchema()));
            } catch (Exception e) {
                // parametersSchema 是我们自己写的常量，解析失败说明写错了，抛出来让启动期就发现
                throw new IllegalStateException("工具 " + tool.name() + " 的 parametersSchema 不是合法 JSON", e);
            }
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private String writeRequestJson(Map<String, Object> body) {
        try {
            return requestMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("上游请求体序列化失败", e);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }

    static ChatModelGateway.ChatChunk toChunk(String dataPayload) {
        if (dataPayload == null || dataPayload.isBlank() || "[DONE]".equals(dataPayload.trim())) {
            return null;
        }
        UpstreamChunk chunk;
        try {
            chunk = MAPPER.readValue(dataPayload, UpstreamChunk.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("上游帧不是合法 JSON: " + e.getMessage(), e);
        }
        String text = null;
        String reasoning = null;
        String finish = null;
        List<ChatModelGateway.ToolCallDelta> toolCalls = null;
        List<UpstreamChunk.Choice> choices = chunk.choices();
        if (choices != null && !choices.isEmpty()) {
            UpstreamChunk.Choice choice = choices.get(0);
            UpstreamChunk.Delta delta = choice.delta();
            if (delta != null) {
                text = nonBlank(delta.content());
                reasoning = nonBlank(delta.reasoningContent());
                toolCalls = toToolCallDeltas(delta.toolCalls());
            }
            finish = lower(choice.finishReason());
        }
        Integer pt = null;
        Integer ct = null;
        Integer rt = null;
        UpstreamChunk.Usage u = chunk.usage();
        if (u != null) {
            pt = positive(u.promptTokens());
            ct = positive(u.completionTokens());
            rt = u.completionTokenDetails() == null ? null
                    : positive(u.completionTokenDetails().reasoningTokens());
        }
        if (text == null && reasoning == null && finish == null && pt == null && ct == null && rt == null
                && (toolCalls == null || toolCalls.isEmpty())) {
            return null;
        }
        return new ChatModelGateway.ChatChunk(text, reasoning, finish, pt, ct, rt, toolCalls);
    }

    /**
     * 把上游 delta.tool_calls 转成我们的归一化形状。
     * 流式时 function.name 和 arguments 可能分散在多帧，这里原样带出 index，
     * 调用方按 index 拼 arguments；name 只在有值的帧带，调用方取第一次出现的即可。
     */
    private static List<ChatModelGateway.ToolCallDelta> toToolCallDeltas(List<UpstreamChunk.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        List<ChatModelGateway.ToolCallDelta> out = new ArrayList<>(calls.size());
        for (UpstreamChunk.ToolCall c : calls) {
            String name = c.function() == null ? null : c.function().name();
            String args = c.function() == null ? null : c.function().arguments();
            out.add(new ChatModelGateway.ToolCallDelta(c.index(), c.id(), name, args));
        }
        return out;
    }

    private static String nonBlank(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static String lower(String v) {
        if (v == null) {
            return null;
        }
        // 归一是 R17 的教训：上游/框架交付过 "LENGTH"，而编排层按小写常量比较，
        // 不归一的话被 max_tokens 截断的回答会记成 done。
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }
}
