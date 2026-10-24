package org.leon.aimodule.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.leon.aimodule.chat.web.dto.ChatUsageVo;
import org.leon.aimodule.chat.web.dto.MessagePageVo;
import org.leon.aimodule.chat.web.dto.MessageVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MessageQueryService {

    private static final int MAX_PAGE = 100;

    private final AiMessageMapper messageMapper;
    private final ConversationService conversations;

    public MessageQueryService(AiMessageMapper messageMapper, ConversationService conversations) {
        this.messageMapper = messageMapper;
        this.conversations = conversations;
    }

    /**
     * superseded 不进对话流：重新生成后旧回答只在库里留档，不在 UI 出现（spec 4.2 #6）。
     * streaming 行会返回，前端据此显示"正在生成"。
     */
    public MessagePageVo page(String userName, Long conversationId, Long beforeId, int limit) {
        conversations.requireOwned(userName, conversationId);
        int size = conversations.clampLimit(limit, MAX_PAGE);
        LambdaQueryWrapper<AiMessage> q = new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .ne(AiMessage::getStatus, AiMessage.ST_SUPERSEDED);
        if (beforeId != null) {
            q.lt(AiMessage::getId, beforeId);
        }
        q.orderByDesc(AiMessage::getId).last("LIMIT " + (size + 1));
        List<AiMessage> rows = messageMapper.selectList(q);
        boolean hasMore = rows.size() > size;
        List<MessageVo> items = new ArrayList<>(
                (hasMore ? rows.subList(0, size) : rows).stream().map(this::toVo).toList());
        Collections.reverse(items);
        return new MessagePageVo(hasMore, items);
    }

    private MessageVo toVo(AiMessage m) {
        ChatUsageVo usage = m.getPromptTokens() == null && m.getCompletionTokens() == null
                ? null : new ChatUsageVo(m.getPromptTokens(), m.getCompletionTokens());
        return new MessageVo(m.getId(), m.getConversationId(), m.getRole(), m.getContent(), m.getStatus(),
                m.getModelKey(), m.getEffort(), m.getStopReason(), usage, ChatTime.millis(m.getCreateTime()));
    }
}
