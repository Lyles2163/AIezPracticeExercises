package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StreamRequest(@NotNull(message = "conversationId 不能为空") Long conversationId,
                            @Size(max = 32000, message = "单条消息过长") String content,
                            String modelKey, String effort,
                            @Size(max = 40, message = "clientMsgId 最长 40 字符") String clientMsgId,
                            boolean regenerate) {
}
