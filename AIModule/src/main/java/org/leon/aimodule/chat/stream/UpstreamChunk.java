package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 上游一帧的原始形状。ignoreUnknown 是硬要求：DeepSeek 已经在发
 * system_fingerprint / prompt_cache_hit_tokens 这类我们没建模的字段，
 * 严格绑定就是等着某天 500。
 *
 * 每个字段都写显式 @JsonProperty，不用 SNAKE_CASE 命名策略：实测
 * "命名策略 + record 隐式组件名" 的绑定只在特定属性顺序下生效 ——
 * 真实的 usage 尾帧（prompt_tokens / completion_tokens / completion_tokens_details
 * 三个属性）会把 completion_tokens_details 静默丢成 null，两个属性时却正常。
 * 这种"看运气"的绑定不能留在计费字段上。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record UpstreamChunk(@JsonProperty("choices") List<Choice> choices,
                     @JsonProperty("usage") Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(@JsonProperty("delta") Delta delta,
                  @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(@JsonProperty("content") String content,
                 @JsonProperty("reasoning_content") String reasoningContent,
                 @JsonProperty("tool_calls") List<ToolCall> toolCalls) {
    }

    /**
     * 上游流式 tool_calls 的增量。注意：流式时每个 choice 的 tool_calls 数组通常只带一个元素，
     * index 字段标识这是第几个工具调用；arguments 是逐段拼接的 JSON 字符串片段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCall(@JsonProperty("index") Integer index,
                    @JsonProperty("id") String id,
                    @JsonProperty("type") String type,
                    @JsonProperty("function") FunctionCall function) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionCall(@JsonProperty("name") String name,
                        @JsonProperty("arguments") String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                 @JsonProperty("completion_tokens") Integer completionTokens,
                 @JsonProperty("completion_tokens_details") CompletionTokenDetails completionTokenDetails) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompletionTokenDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
    }
}
