package org.leon.aimodule.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 桌宠那条链的会话归属：一个账号下固定一条「桌宠对话」。
 *
 * 为什么固定一条：dsh-pet 的请求体里没有任何会话标识（只有 model/messages/max_tokens/n），
 * 鉴权头也写死了塞不进自定义头，后端拿不到「这是哪一段对话」的信号，只能自己定策略。
 * 独立会话还顺带隔开了两条链的互斥——BUSY 判定按 conversationId 走，
 * 网页端正在生成时桌宠照样能发。
 *
 * 标题是显式给的，所以不会被 renameIfDefault 改成首句问题（那条 SQL 只认「新对话」）。
 */
@Component
public class OpenAiCompatConversations {

    private final AiConversationMapper conversationMapper;
    private final ConversationService conversations;
    private final ChatProperties props;

    public OpenAiCompatConversations(AiConversationMapper conversationMapper, ConversationService conversations,
                                     ChatProperties props) {
        this.conversationMapper = conversationMapper;
        this.conversations = conversations;
        this.props = props;
    }

    /** 找到就用，没有就建。并发下万一建出两条，按 id 升序取最早的那条，重复自然收敛。 */
    @Transactional
    public Long ensure(String userName, String modelKey) {
        String title = props.getOpenaiCompat().getConversationTitle();
        List<AiConversation> found = conversationMapper.selectList(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserName, userName)
                .eq(AiConversation::getTitle, title)
                .eq(AiConversation::getStatus, 1)
                .orderByAsc(AiConversation::getId));
        if (!found.isEmpty()) {
            return found.get(0).getId();
        }
        ConversationVo created = conversations.create(userName,
                new CreateConversationRequest(title, modelKey, props.getOpenaiCompat().getEffort()));
        return created.id();
    }
}
