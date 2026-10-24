package org.leon.aimodule.chat.web.dto;

import java.util.List;

public record MessagePageVo(boolean hasMore, List<MessageVo> items) {
}
