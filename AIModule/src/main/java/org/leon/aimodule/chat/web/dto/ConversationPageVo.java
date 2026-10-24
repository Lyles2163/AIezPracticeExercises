package org.leon.aimodule.chat.web.dto;

import java.util.List;

public record ConversationPageVo(List<ConversationVo> items, String nextCursor) {
}
