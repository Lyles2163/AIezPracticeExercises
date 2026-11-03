package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.exception.OpenAiRejectException;
import org.leon.aimodule.chat.service.OpenAiCompatAuthenticator;
import org.leon.aimodule.chat.service.OpenAiCompatConversations;
import org.leon.aimodule.chat.stream.ChatStreamService;
import org.leon.aimodule.chat.stream.OpenAiSseChannel;
import org.leon.aimodule.chat.stream.PreparedTurn;
import org.leon.aimodule.chat.stream.SseChannel;
import org.leon.aimodule.chat.stream.StreamCommand;
import org.leon.aimodule.chat.web.dto.OpenAiChatRequest;
import org.leon.aimodule.chat.web.dto.OpenAiError;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 桌宠（dsh-pet 独立模式）的 OpenAI 兼容入口：{@code POST /ai/v1/chat/completions}。
 *
 * 它存在的唯一理由是 dsh-pet 只会说 OpenAI/Anthropic 两种协议，而你们的会话管线是自定义 SSE 契约。
 * 因此这层只做「翻协议」，不新增业务：取末条 user 落库、历史交给 ContextAssembler 从库里组
 * （两处都带会翻倍）、人设走 systemPromptOverride 透传、RAG 开着。
 *
 * 与 {@link ChatStreamController} 的三点不同，都是协议逼出来的：
 * 1. 不要求 {@code X-User-Name}：这条路径在网关白名单里，归属账号由服务键配置决定；
 * 2. 错误必须带 4xx 与 {@code {"error":{...}}}：本包的 advice 一律回 HTTP 200 + Result，
 *    官方 SDK 解析不了那个形状，所以这里用控制器局部的 @ExceptionHandler 抢在 advice 之前接管；
 * 3. 帧没有事件名，结尾补一条 {@code data: [DONE]}。
 */
@RestController
@RequestMapping("/ai/v1")
public class OpenAiCompatController {

    private final ChatStreamService service;
    private final OpenAiCompatAuthenticator auth;
    private final OpenAiCompatConversations conversations;
    private final ChatProperties props;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeat;

    public OpenAiCompatController(ChatStreamService service, OpenAiCompatAuthenticator auth,
                                  OpenAiCompatConversations conversations, ChatProperties props,
                                  ObjectMapper mapper,
                                  @Qualifier("chatHeartbeatExecutor") ScheduledExecutorService heartbeat) {
        this.service = service;
        this.auth = auth;
        this.conversations = conversations;
        this.props = props;
        this.mapper = mapper;
        this.heartbeat = heartbeat;
    }

    @PostMapping(value = "/chat/completions",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter completions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody OpenAiChatRequest req,
            HttpServletResponse response) {

        OpenAiCompatAuthenticator.Decision decision = auth.authorize(authorization);
        if (decision == OpenAiCompatAuthenticator.Decision.DISABLED) {
            throw new OpenAiRejectException(503, "route_disabled", "桌宠对话链未配置服务键或归属账号，路由已关闭");
        }
        if (decision == OpenAiCompatAuthenticator.Decision.REJECTED) {
            throw new OpenAiRejectException(401, "invalid_api_key", "服务键缺失或不正确");
        }
        if (req.model() == null || req.model().isBlank() || props.model(req.model()) == null) {
            throw OpenAiRejectException.badRequest("model_not_found", "模型不在服务端的目录里: " + req.model());
        }
        if (!Boolean.TRUE.equals(req.stream())) {
            throw OpenAiRejectException.badRequest("stream_required", "这条链只支持流式（stream: true）");
        }
        Turn turn = Turn.from(req.messages());

        String userName = auth.userName();
        Long conversationId = conversations.ensure(userName, req.model());
        PreparedTurn prepared = service.prepare(new StreamCommand(conversationId, turn.content(), req.model(),
                props.getOpenaiCompat().getEffort(), null, false, true, null, null, null, turn.persona()), userName);

        // 与 #7 同一组响应头：代理侧不聚合，Nginx 还要 proxy_buffering off。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        // SDK 发的是 Accept: application/json，协商会把内容类型定成 JSON；帧体不受影响，
        // 但让任何按内容类型嗅探的代理看到 text/event-stream 更保险（emitter 只在头未设时补默认值）。
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        SseEmitter emitter = new SseEmitter(props.getStreamTimeoutMs());
        OpenAiSseChannel channel = new OpenAiSseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) throws IOException {
                SseEmitter.SseEventBuilder builder = SseEmitter.event().data(json);
                emitter.send(event == null ? builder : builder.name(event));
            }

            @Override
            public void complete() {
                // [DONE] 是 OpenAI 流的终止符；写不出去也只是客户端提前收尾，不能让它掀翻收尾流程。
                try {
                    emitter.send(SseEmitter.event().data("[DONE]"));
                } catch (Exception ignored) {
                    // 连接已经断了
                }
                emitter.complete();
            }
        }, mapper, "chatcmpl-" + prepared.assistantMessageId(), req.model());

        int interval = props.getHeartbeatIntervalSeconds();
        ScheduledFuture<?> ping = heartbeat.scheduleAtFixedRate(() -> {
            try {
                channel.ping();
            } catch (SseChannel.ClientGoneException ignored) {
                // emitter 已经断了
            }
        }, interval, interval, TimeUnit.SECONDS);

        emitter.onTimeout(() -> {
            ping.cancel(false);
            service.timeout(prepared.assistantMessageId());
        });
        Runnable cancelPing = () -> {
            ping.cancel(false);
            service.clientGone(prepared.assistantMessageId());
        };
        emitter.onError(t -> cancelPing.run());
        emitter.onCompletion(cancelPing);

        service.subscribeAsync(prepared, channel);
        return emitter;
    }

    /** 业务拒绝翻成 400：会话忙、消息过长这类，桌宠面板要能看见原因。 */
    @ExceptionHandler(ChatRejectException.class)
    public void onReject(ChatRejectException e, HttpServletResponse response) throws IOException {
        writeError(response, 400, "conversation_rejected", e.getMessage());
    }

    @ExceptionHandler(OpenAiRejectException.class)
    public void onOpenAiReject(OpenAiRejectException e, HttpServletResponse response) throws IOException {
        writeError(response, e.status(), e.code(), e.getMessage());
    }

    /** 请求体不是合法 JSON 时，不能让 advice 把它变成 HTTP 200 的 Result。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void onUnreadable(HttpMessageNotReadableException e, HttpServletResponse response) throws IOException {
        writeError(response, 400, "invalid_request_error", "请求体不是合法的 JSON");
    }

    @ExceptionHandler(Exception.class)
    public void onUnexpected(Exception e, HttpServletResponse response) throws IOException {
        writeError(response, 500, "server_error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /**
     * 手写响应，不走内容协商：SDK 的 Accept 是 application/json，而本方法又声明了
     * produces=text/event-stream，交给协商机制很容易变成空响应体（#7 的 advice 同一原因）。
     */
    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), OpenAiError.of(message, code));
    }

    /**
     * 从 messages 里取出这一轮要落库的话与人设。
     *
     * 只取末条 user：历史由 ContextAssembler 按 token 预算从库里组，桌宠自带的那份必须丢，
     * 否则同一段对话会在上下文里出现两遍。
     * 图片明确拒绝（不静默丢）：桌宠的贴图按钮这条链没接通，悄悄丢掉等于骗用户发出去了。
     */
    private static final class Turn {

        private final String content;
        private final String persona;

        private Turn(String content, String persona) {
            this.content = content;
            this.persona = persona;
        }

        String content() {
            return content;
        }

        String persona() {
            return persona;
        }

        static Turn from(List<OpenAiChatRequest.Message> messages) {
            if (messages == null || messages.isEmpty()) {
                throw OpenAiRejectException.badRequest("missing_messages", "messages 不能为空");
            }
            String content = null;
            String persona = null;
            for (OpenAiChatRequest.Message m : messages) {
                String text = textOf(m);
                if ("system".equals(m.role())) {
                    if (persona == null && text != null && !text.isBlank()) {
                        persona = text;
                    }
                } else if ("user".equals(m.role())) {
                    content = text;
                }
            }
            if (content == null || content.isBlank()) {
                throw OpenAiRejectException.badRequest("missing_user_message", "messages 里没有可用的用户消息");
            }
            return new Turn(content, persona);
        }

        private static String textOf(OpenAiChatRequest.Message m) {
            if (m.content() == null || m.content().isNull()) {
                return null;
            }
            if (m.content().isTextual()) {
                return m.content().asText();
            }
            if (!m.content().isArray()) {
                throw OpenAiRejectException.badRequest("unsupported_content", "content 只能是字符串或部件数组");
            }
            StringBuilder sb = new StringBuilder();
            for (var part : m.content()) {
                String type = part.path("type").asText("");
                if (!"text".equals(type)) {
                    throw OpenAiRejectException.badRequest("unsupported_content_part",
                            "这条链不支持 " + (type.isEmpty() ? "未标注类型" : type) + " 的内容部件");
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part.path("text").asText(""));
            }
            return sb.toString();
        }
    }
}
