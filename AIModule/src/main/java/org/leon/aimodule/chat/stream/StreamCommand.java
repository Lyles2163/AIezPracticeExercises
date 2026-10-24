package org.leon.aimodule.chat.stream;

public record StreamCommand(Long conversationId, String content, String modelKey, String effort,
                            String clientMsgId, boolean regenerate) {
}
