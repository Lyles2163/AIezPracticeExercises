package org.leon.aimodule.chat.service;

import java.util.List;

/**
 * 组装后发给模型的一条上下文消息。
 *
 * 普通对话只用 role + content；工具调用时还会用到 toolCalls 和 toolCallId：
 * - 模型返回 tool_calls 的那条 assistant 消息：role=assistant, content 可能为空, toolCalls 非空；
 * - 工具执行结果消息：role=tool, toolCallId 关联到发起调用的那条, content 是结果字符串。
 */
public record CtxMessage(String role, String content, List<ToolCallRef> toolCalls, String toolCallId) {

    public CtxMessage(String role, String content) {
        this(role, content, null, null);
    }

    /** 一次工具调用的引用（发回给模型时用）。 */
    public record ToolCallRef(String id, String name, String arguments) {
    }
}
