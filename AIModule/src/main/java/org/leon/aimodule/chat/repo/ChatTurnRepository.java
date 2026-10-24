package org.leon.aimodule.chat.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话写路径的唯一入口。存在的理由只有一个：让"一次回答写库几次"可以在
 * 一个 mock 对象上被数出来（见 ChatStreamWriteCountTest）。
 */
@Repository
public class ChatTurnRepository {

    public static final int PREVIEW_CP = 80;
    static final String REAP_REASON = "生成中断（进程重启或超时）";

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;

    public ChatTurnRepository(AiMessageMapper messageMapper, AiConversationMapper conversationMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
    }

    @Transactional
    public Long insertUser(Long cid, String content, String clientMsgId) {
        AiMessage m = new AiMessage();
        m.setConversationId(cid);
        m.setRole(AiMessage.ROLE_USER);
        m.setContent(content);
        m.setStatus(AiMessage.ST_DONE);
        m.setClientMsgId(clientMsgId);
        messageMapper.insert(m);
        return m.getId();
    }

    @Transactional
    public Long insertAssistant(Long cid, Long replyTo, String modelKey, String effort,
                                String assistantClientMsgId) {
        AiMessage m = new AiMessage();
        m.setConversationId(cid);
        m.setRole(AiMessage.ROLE_ASSISTANT);
        m.setContent("");
        m.setStatus(AiMessage.ST_STREAMING);
        m.setReplyToMessageId(replyTo);
        m.setModelKey(modelKey);
        m.setEffort(effort);
        m.setClientMsgId(assistantClientMsgId);
        messageMapper.insert(m);
        return m.getId();
    }

    public AiMessage findById(Long id) {
        return id == null ? null : messageMapper.selectById(id);
    }

    public AiMessage findByClientMsgId(Long cid, String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return null;
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, cid)
                .eq(AiMessage::getClientMsgId, clientMsgId)
                .last("LIMIT 1"));
    }

    /** 生效中的历史，升序，不含本轮这条新用户消息。 */
    public List<AiMessage> activeHistory(Long cid, Long beforeIdExclusive) {
        LambdaQueryWrapper<AiMessage> q = new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, cid)
                .in(AiMessage::getStatus, AiMessage.ST_DONE, AiMessage.ST_TRUNCATED);
        if (beforeIdExclusive != null) {
            q.lt(AiMessage::getId, beforeIdExclusive);
        }
        return messageMapper.selectList(q.orderByAsc(AiMessage::getId));
    }

    public void supersede(Long replyTo) {
        messageMapper.supersedePrevious(replyTo);
    }

    public void touch(Long cid, Long lastMessageId, String preview, int delta) {
        conversationMapper.touchOnNewMessage(cid, lastMessageId, ChatTime.truncate(preview, PREVIEW_CP), delta);
    }

    public int renameIfDefault(Long cid, String title) {
        return conversationMapper.renameIfDefault(cid, ChatTime.truncate(title, 24));
    }

    public int reapStale(Long cid, int minutes) {
        return messageMapper.reapStaleStreaming(cid, minutes, REAP_REASON);
    }

    public int countLive(Long cid, int minutes) {
        return messageMapper.countLiveStreaming(cid, minutes);
    }

    @Transactional
    public void finalizeAssistant(Long id, String content, String status, String stopReason,
                                  Integer promptTokens, Integer completionTokens) {
        messageMapper.finalizeAssistant(id, content, status, stopReason, promptTokens, completionTokens);
    }

    /** 上游失败：内容与错误原因一次 UPDATE 写完，不拆成 finalize + markFailed 两次。 */
    @Transactional
    public void finalizeFailed(Long id, String content, String errorMsg) {
        messageMapper.finalizeFailed(id, content, ChatTime.truncate(errorMsg, 250));
    }

    public void markFailed(Long id, String errorMsg) {
        messageMapper.markFailed(id, ChatTime.truncate(errorMsg, 250));
    }
}
