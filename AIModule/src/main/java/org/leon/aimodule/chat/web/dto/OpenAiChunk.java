package org.leon.aimodule.chat.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * OpenAI 的流式响应帧（{@code object = chat.completion.chunk}）。
 *
 * choices 为空数组是合法的 keepalive：OpenAI 自己在空闲时就发这种帧，
 * SDK 会跳过它。桌宠这条链的心跳因此不用发明新事件名。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChunk(String id, String object, long created, String model, List<Choice> choices) {

    public static OpenAiChunk of(String id, long created, String model, List<Choice> choices) {
        return new OpenAiChunk(id, "chat.completion.chunk", created, model, choices);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(int index, Delta delta, String finish_reason) {

        public static Choice delta(Delta delta) {
            return new Choice(0, delta, null);
        }

        public static Choice finish(String reason) {
            return new Choice(0, new Delta(null, null), reason);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {

        public static Delta role(String role) {
            return new Delta(role, null);
        }

        public static Delta content(String content) {
            return new Delta(null, content);
        }
    }
}
