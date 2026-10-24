package org.leon.aimodule.chat.web.dto;

public record MessageVo(Long id, Long conversationId, String role, String content, String status,
                        String modelKey, String effort, String stopReason, ChatUsageVo usage, Long createTime) {
}
