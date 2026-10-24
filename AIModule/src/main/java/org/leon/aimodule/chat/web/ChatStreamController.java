package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.stream.ChatStreamService;
import org.leon.aimodule.chat.stream.PreparedTurn;
import org.leon.aimodule.chat.stream.SseChannel;
import org.leon.aimodule.chat.stream.StreamCommand;
import org.leon.aimodule.chat.web.dto.StreamRequest;
import org.leon.commonjwt.pojo.Result;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * produces 同时声明 SSE 与 JSON：建流前的业务拒绝要返回 Result(JSON)，
 * 若只声明 text/event-stream，客户端带 Accept: text/event-stream 时
 * advice 的 JSON 体协商不出来，会变成 406。
 */
@RestController
@RequestMapping("/ai/chat")
@Validated
public class ChatStreamController {

    private final ChatStreamService service;
    private final ObjectMapper mapper;
    private final ChatProperties props;
    private final ScheduledExecutorService heartbeat;

    public ChatStreamController(ChatStreamService service, ObjectMapper mapper, ChatProperties props,
                                @Qualifier("chatHeartbeatExecutor") ScheduledExecutorService heartbeat) {
        this.service = service;
        this.mapper = mapper;
        this.props = props;
        this.heartbeat = heartbeat;
    }

    @PostMapping(value = "/completions/stream",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter stream(@RequestHeader("X-User-Name") String userName,
                             @RequestBody @Valid StreamRequest req,
                             HttpServletResponse response) {
        if (!req.regenerate() && (req.content() == null || req.content().isBlank())) {
            throw new ChatRejectException("content 不能为空");
        }
        PreparedTurn prepared = service.prepare(new StreamCommand(req.conversationId(), req.content(),
                req.modelKey(), req.effort(), req.clientMsgId(), req.regenerate()), userName);

        // 代理侧不聚合响应体；Nginx 还需 proxy_buffering off。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(props.getStreamTimeoutMs());
        SseChannel channel = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) throws IOException {
                emitter.send(SseEmitter.event().name(event).data(json));
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        }, mapper);

        // 心跳独立于业务事件：空闲太久会被代理断流。ping 失败不重抛——
        // 抛出去 ScheduledExecutorService 会静默停掉后续调度，收尾交给 emitter 的回调。
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

    @PostMapping("/messages/{id}/stop")
    public Result<Void> stop(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        service.stop(id, userName);
        return Result.success();
    }
}
