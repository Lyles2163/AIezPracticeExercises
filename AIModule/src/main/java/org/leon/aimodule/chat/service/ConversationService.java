package org.leon.aimodule.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.leon.aimodule.chat.support.CursorCodec;
import org.leon.aimodule.chat.web.dto.ConversationPageVo;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.leon.aimodule.chat.web.dto.ModelCatalogVo;
import org.leon.aimodule.chat.web.dto.ModelVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    static final String DEFAULT_TITLE = "新对话";
    static final int PREVIEW_CP = 80;

    private final ChatProperties props;
    private final EffortResolver resolver;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    public ConversationService(ChatProperties props, EffortResolver resolver,
                              AiConversationMapper conversationMapper, AiMessageMapper messageMapper) {
        this.props = props;
        this.resolver = resolver;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    public ModelCatalogVo catalog() {
        return new ModelCatalogVo(props.getDefaultModel(), props.getDefaultEffort(),
                props.getEfforts().stream().map(ChatProperties.Effort::getKey).toList(),
                props.getModels().stream()
                        .map(m -> new ModelVo(m.getKey(), m.getName(), m.getTag(),
                                props.thinkingSupported(m.isSupportsThinking())))
                        .toList());
    }

    @Transactional
    public ConversationVo create(String userName, CreateConversationRequest req) {
        ModelParams params = resolver.resolve(req == null ? null : req.modelKey(),
                req == null ? null : req.effort());
        AiConversation c = new AiConversation();
        c.setUserName(userName);
        c.setTitle(defaultTitle(req == null ? null : req.title()));
        c.setModelKey(params.modelKey());
        c.setEffort(effortOf(req));
        conversationMapper.insert(c);
        // 插入只回填自增 id；update_time 等 DB 默认列必须按主键回读，保证与 #3 列表同一时间基线
        AiConversation saved = conversationMapper.selectById(c.getId());
        return toVo(saved);
    }

    public ConversationPageVo list(String userName, String cursor, int limit) {
        int size = clampLimit(limit, 50);
        CursorCodec.Cursor c = CursorCodec.decode(cursor);
        LambdaQueryWrapper<AiConversation> q = new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserName, userName)
                .eq(AiConversation::getStatus, 1);
        if (c != null) {
            // MySQL DATETIME 只接受 1000–9999 年；超界游标在 JDBC 层会变成内部错误
            int year = c.updateTime().getYear();
            if (year < 1000 || year > 9999) {
                throw new ChatRejectException("cursor 无效");
            }
            q.and(w -> w.lt(AiConversation::getUpdateTime, c.updateTime())
                    .or(x -> x.eq(AiConversation::getUpdateTime, c.updateTime())
                            .lt(AiConversation::getId, c.id())));
        }
        q.orderByDesc(AiConversation::getUpdateTime).orderByDesc(AiConversation::getId)
                .last("LIMIT " + (size + 1));
        List<AiConversation> rows = conversationMapper.selectList(q);
        boolean more = rows.size() > size;
        List<AiConversation> page = more ? rows.subList(0, size) : rows;
        List<ConversationVo> items = page.stream().map(this::toVo).toList();
        String next = (more && !page.isEmpty())
                ? CursorCodec.encode(page.get(page.size() - 1).getUpdateTime(),
                        page.get(page.size() - 1).getId())
                : null;
        return new ConversationPageVo(items, next);
    }

    @Transactional
    public void rename(String userName, Long id, String title) {
        requireOwned(userName, id);
        AiConversation upd = new AiConversation();
        upd.setId(id);
        upd.setTitle(ChatTime.truncate(title, 120));
        conversationMapper.updateById(upd);
    }

    @Transactional
    public void softDelete(String userName, Long id) {
        requireOwned(userName, id);
        AiConversation upd = new AiConversation();
        upd.setId(id);
        upd.setStatus(0);
        conversationMapper.updateById(upd);
    }

    public AiConversation requireOwned(String userName, Long id) {
        AiConversation c = id == null ? null : conversationMapper.selectById(id);
        if (c == null || !c.getUserName().equals(userName) || c.getStatus() == null || c.getStatus() != 1) {
            throw new ChatRejectException("会话不存在");
        }
        return c;
    }

    public int clampLimit(int limit, int max) {
        return Math.min(Math.max(limit, 1), max);
    }

    private String defaultTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return ChatTime.truncate(title, 120);
    }

    private String effortOf(CreateConversationRequest req) {
        String ek = req == null || req.effort() == null || req.effort().isBlank()
                ? props.getDefaultEffort() : req.effort().trim();
        return ek;
    }

    ConversationVo toVo(AiConversation c) {
        return new ConversationVo(c.getId(), c.getTitle(), c.getModelKey(), c.getEffort(),
                c.getMessageCount(), c.getLastPreview(), ChatTime.millis(c.getUpdateTime()));
    }
}
