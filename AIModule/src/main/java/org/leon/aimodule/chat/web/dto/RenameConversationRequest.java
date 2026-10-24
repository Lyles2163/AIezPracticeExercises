package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameConversationRequest(
        @NotBlank(message = "标题不能为空") @Size(min = 1, max = 120, message = "标题最长 120 字符") String title) {
}
