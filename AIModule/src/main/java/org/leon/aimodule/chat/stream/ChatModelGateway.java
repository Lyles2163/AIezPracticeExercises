package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.leon.aimodule.tool.ChatTool;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatModelGateway {

    /**
     * @param systemPromptOverride 非空时覆盖配置里的 system prompt，用于注入 RAG 检索上下文
     * @param tools                可调用的工具列表；为空或 null 时按无工具模式请求
     */
    Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params, String systemPromptOverride,
                           List<ChatTool> tools);

    default Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params, String systemPromptOverride) {
        return stream(messages, params, systemPromptOverride, null);
    }

    default Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params) {
        return stream(messages, params, null, null);
    }

    /**
     * 上游一帧的归一化视图，七个字段都可能为 null。
     * toolCalls 非空时表示模型发起了工具调用，调用方要执行工具并把结果作为新消息继续请求。
     */
    record ChatChunk(String text, String reasoning, String finishReason,
                     Integer promptTokens, Integer completionTokens, Integer reasoningTokens,
                     List<ToolCallDelta> toolCalls) {

        public ChatChunk(String text, String reasoning, String finishReason,
                         Integer promptTokens, Integer completionTokens, Integer reasoningTokens) {
            this(text, reasoning, finishReason, promptTokens, completionTokens, reasoningTokens, null);
        }
    }

    /**
     * 一次工具调用的增量。流式时 id/name 通常只在第一帧出现，arguments 是逐段拼接的字符串片段。
     * 调用方按 index 分组，把同 index 的 arguments 片段拼成完整 JSON 后再执行工具。
     */
    record ToolCallDelta(Integer index, String id, String name, String arguments) {
    }
}
