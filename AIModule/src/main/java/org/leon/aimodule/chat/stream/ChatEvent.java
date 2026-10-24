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

    record TokenUsage(Integer promptTokens, Integer completionTokens) implements ChatEvent {
    }

    /** finishReason: done / stopped / length / upstream */
    record Done(String finishReason) implements ChatEvent {
    }

    record ErrorEvt(String errorCode, String msg, boolean retryable) implements ChatEvent {
    }
}
