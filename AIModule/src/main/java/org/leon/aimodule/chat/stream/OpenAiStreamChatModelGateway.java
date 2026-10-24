package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
    public Flux<ChatModelGateway.ChatChunk> stream(List<CtxMessage> messages, ModelParams params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", params.modelKey());
        body.put("messages", toWireMessages(messages));
        body.put("stream", true);
        // 不发这一行就没有 usage 帧 —— 上一期是靠 spring.ai.openai...stream-usage 让库代发。
        body.put("stream_options", Map.of("include_usage", true));
        if (params.maxTokens() != null) {
            body.put("max_tokens", params.maxTokens());
        }
        if (params.temperature() != null) {
            body.put("temperature", params.temperature());
        }
        String json = writeRequestJson(body);
        String base = testBaseUrl == null ? props.getBaseUrl() : testBaseUrl;
        return webClient.mutate().baseUrl(base).build()
                .post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
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
                                    resp.statusCode().value() + " from POST /v1/chat/completions: "
                                            + truncate(text))));
                })
                // 心跳只保活"我们→前端"，保不了"上游→我们"这一跳，所以这里必须有空闲判定。
                .timeout(Duration.ofSeconds(props.getUpstreamIdleTimeoutSeconds()))
                .mapNotNull(sse -> toChunk(sse.data()));
    }

    /** system 提示放第一位，与上一期 ChatClient.system() 的线上形状一致。 */
    private List<Map<String, String>> toWireMessages(List<CtxMessage> messages) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!props.getSystemPrompt().isBlank()) {
            out.add(Map.of("role", "system", "content", props.getSystemPrompt()));
        }
        for (CtxMessage m : messages) {
            out.add(Map.of("role", m.role(), "content", m.content() == null ? "" : m.content()));
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
        List<UpstreamChunk.Choice> choices = chunk.choices();
        if (choices != null && !choices.isEmpty()) {
            UpstreamChunk.Choice choice = choices.get(0);
            UpstreamChunk.Delta delta = choice.delta();
            if (delta != null) {
                text = nonBlank(delta.content());
                reasoning = nonBlank(delta.reasoningContent());
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
        if (text == null && reasoning == null && finish == null && pt == null && ct == null && rt == null) {
            return null;
        }
        return new ChatModelGateway.ChatChunk(text, reasoning, finish, pt, ct, rt);
    }

    private static String nonBlank(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static String lower(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }
}
