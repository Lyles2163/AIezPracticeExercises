package org.leon.aimodule.chat.web.dto;

public record ConversationVo(Long id, String title, String modelKey, String effort,
                             Long messageCount, String lastPreview, Long updateTime) {
}
