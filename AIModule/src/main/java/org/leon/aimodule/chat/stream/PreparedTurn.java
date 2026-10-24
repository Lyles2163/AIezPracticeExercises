package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;

import java.util.List;

public record PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                           List<CtxMessage> messages, ModelParams params, String userName, String effortName) {
}
