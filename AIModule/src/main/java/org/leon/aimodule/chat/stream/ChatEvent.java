package org.leon.aimodule.chat.stream;

/**
 * 编排层唯一的输出语言。心跳 ping 不在这里：它由 SseChannel 的定时器直接发，
 * 不经编排层，否则每个业务事件都要被迫携带它。
 */
public sealed interface ChatEvent {

    record Meta(Long conversationId, Long messageId, String modelKey, String effort,
                boolean supportsThinking) implements ChatEvent {
    }

    record Delta(String text) implements ChatEvent {
    }

    /** 一条可读推理短句。kind/seq 是故意不做的：只有 reasoning 一种生产者。 */
    record ThoughtStep(String text) implements ChatEvent {
    }

    /** seconds 单位是秒（小数）—— 前端 ThoughtLine 的 elapsed 就是这个单位。 */
    record ThoughtEnd(double seconds) implements ChatEvent {
    }

    /** reasoningTokens 是 completionTokens 的拆分，不是加数。 */
    record TokenUsage(Integer promptTokens, Integer completionTokens,
                      Integer reasoningTokens) implements ChatEvent {
    }

    /** finishReason: done / stopped / length / upstream */
    record Done(String finishReason) implements ChatEvent {
    }

    record ErrorEvt(String errorCode, String msg, boolean retryable) implements ChatEvent {
    }
}
