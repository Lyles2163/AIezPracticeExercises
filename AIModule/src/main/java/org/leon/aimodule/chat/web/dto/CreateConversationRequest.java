package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @Size(max = 120, message = "标题最长 120 字符") String title,
        String modelKey,
        String effort) {
}
