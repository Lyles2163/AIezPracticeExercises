package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 发帧的唯一入口。SseEmitter.send 不是幂等追加：心跳由定时器线程触发、
 * delta 由 Reactor IO 线程触发，两者并发写同一个 response 会交错出坏帧，
 * 所以 send/complete 全部串在一把锁上。
 */
public class SseChannel {

    public interface Sink {
        void send(String event, String json) throws Exception;

        void complete();
    }

    private final Sink sink;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private volatile boolean closed;

    public SseChannel(Sink sink, ObjectMapper mapper) {
        this.sink = sink;
        this.mapper = mapper;
    }

    public boolean isClosed() {
        return closed;
    }

    // switch 对 sealed 类型穷尽：将来给 ChatEvent 加分支而忘记映射帧名，这里编译不过
    public void emit(ChatEvent event) {
        switch (event) {
            case ChatEvent.Meta e -> send("meta", e);
            case ChatEvent.Delta e -> send("delta", e);
            case ChatEvent.TokenUsage e -> send("usage", e);
            case ChatEvent.Done e -> send("done", e);
            case ChatEvent.ErrorEvt e -> send("error", e);
        }
    }

    public void ping() {
        send("ping", new Ping(System.currentTimeMillis()));
    }

    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            sink.complete();
        }
    }

    private void send(String name, Object payload) {
        String json;
        try {
            // 必须走 Jackson：正文里的 \n 与 \n\n 手拼出去会直接切断帧。
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败: " + name, e);
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                sink.send(name, json);
            } catch (Exception e) {
                closed = true;
                throw new ClientGoneException(e);
            }
        }
    }

    private record Ping(long t) {
    }

    /** 客户端断连或写失败，编排层据此落 truncated。 */
    public static class ClientGoneException extends RuntimeException {
        public ClientGoneException(Throwable cause) {
            super("SSE 写出失败", cause);
        }
    }
}
