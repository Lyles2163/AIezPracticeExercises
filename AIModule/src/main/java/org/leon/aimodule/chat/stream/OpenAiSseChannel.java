package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.web.dto.OpenAiChunk;

import java.util.List;

/**
 * 把编排层的 {@link ChatEvent} 翻成 OpenAI 的 chunk 帧（桌宠这条链专用）。
 *
 * 复用父类的锁与 closed 判定：心跳由定时器线程发、delta 由 Reactor IO 线程发，
 * 两条线程写同一个 response 必须串在同一把锁上，所以这里只换帧形、不自建写路径。
 *
 * 映射取舍都记在方法里，翻协议时别再猜：
 * - Meta → 首帧 {@code delta:{role:"assistant"}}（OpenAI 就是这么开头的）；
 * - Delta → {@code delta:{content:...}}；
 * - ThoughtStep / ThoughtEnd / TokenUsage / Sources → 丢弃。OpenAI 的 chunk 里没有承载位，
 *   桌宠那侧也只读 {@code choices[index=0].delta.content}，发出去只会变成乱码；
 *   思考过程与引用编号仍然进落库的正文与 usage，网页端不受影响。
 * - ErrorEvt → 当成正文发出去。流内没有标准错误帧，SDK 解析不了；
 *   让主人看到「模型服务暂时不可用」比看到一条空回复好。
 * - Done → finish_reason；上游失败导致的收尾也报 stop，因为错误已经作为正文发过了，
 *   再报一个协议外的取值只会让客户端解析失败。
 */
public class OpenAiSseChannel extends SseChannel {

    private static final String OBJECT = "chat.completion.chunk";

    private final ObjectMapper mapper;
    private final String chunkId;
    private final long created;
    private final String model;
    private boolean roleSent;

    public OpenAiSseChannel(Sink sink, ObjectMapper mapper, String chunkId, String model) {
        super(sink, mapper);
        this.mapper = mapper;
        this.chunkId = chunkId;
        this.created = System.currentTimeMillis() / 1000L;
        this.model = model;
    }

    @Override
    public void emit(ChatEvent event) {
        switch (event) {
            case ChatEvent.Meta e -> sendRole();
            case ChatEvent.Delta e -> {
                sendRole();
                sendChunk(OpenAiChunk.Choice.delta(OpenAiChunk.Delta.content(e.text())));
            }
            case ChatEvent.ErrorEvt e -> {
                sendRole();
                sendChunk(OpenAiChunk.Choice.delta(OpenAiChunk.Delta.content(e.msg())));
            }
            case ChatEvent.Done e -> sendChunk(OpenAiChunk.Choice.finish(finishReason(e.finishReason())));
            case ChatEvent.ThoughtStep e -> {
            }
            case ChatEvent.ThoughtEnd e -> {
            }
            case ChatEvent.TokenUsage e -> {
            }
            case ChatEvent.Sources e -> {
            }
        }
    }

    /** 空闲保活：OpenAI 自己就用 choices 为空的 chunk 占位，SDK 会跳过这种帧。 */
    @Override
    public void ping() {
        sendRaw(json(OpenAiChunk.of(chunkId, created, model, List.of())), null);
    }

    private void sendRole() {
        if (roleSent) {
            return;
        }
        roleSent = true;
        sendChunk(OpenAiChunk.Choice.delta(OpenAiChunk.Delta.role("assistant")));
    }

    private void sendChunk(OpenAiChunk.Choice choice) {
        sendRaw(json(OpenAiChunk.of(chunkId, created, model, List.of(choice))), null);
    }

    private String json(OpenAiChunk chunk) {
        try {
            return mapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("chunk 序列化失败: " + chunkId, e);
        }
    }

    private String finishReason(String reason) {
        if (AiMessage.STOP_LENGTH.equals(reason)) {
            return "length";
        }
        return "stop";
    }
}
