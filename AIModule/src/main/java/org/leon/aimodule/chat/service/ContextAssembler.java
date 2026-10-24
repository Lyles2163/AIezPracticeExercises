package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.rag.chunk.TokenCounter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端组装多轮上下文：从最新往回按 token 预算累加，再翻正。
 * 计数用 jtokkit cl100k_base，与 DeepSeek 真实分词不一致（中文侧通常偏多），
 * 所以 context-max-tokens 是保守上限而不是精确值。
 * <p>
 * 最新一条用户消息不受截断：它自己就超预算时直接拒绝，绝不静默截断。
 */
@Service
public class ContextAssembler {

    static final String OVERSIZED_MESSAGE = "消息过长，超出上下文预算";

    private final TokenCounter tokenCounter;
    private final ChatProperties props;

    public ContextAssembler(TokenCounter tokenCounter, ChatProperties props) {
        this.tokenCounter = tokenCounter;
        this.props = props;
    }

    /**
     * 组装历史 + 最新用户消息。历史按升序传入，返回的列表同样升序，
     * 且首条一定是 user（否则模型会把 assistant 回答当成自己先开的头）。
     *
     * @param priorAsc          已完成的历史消息，按时间升序，可为 null
     * @param latestUserContent 本次最新用户消息，可为 null（按空串处理）
     * @return 可直接发给模型的上下文消息
     * @throws ChatRejectException 最新消息单独就超出预算时
     */
    public List<CtxMessage> assemble(List<AiMessage> priorAsc, String latestUserContent) {
        int budget = props.getContextMaxTokens();
        String latest = latestUserContent == null ? "" : latestUserContent;
        int used = tokenCounter.count(latest);
        if (used > budget) {
            throw new ChatRejectException(OVERSIZED_MESSAGE);
        }
        // 从最老的历史开始放会被预算卡住；从最新往回放，保证"新"的优先留下
        List<CtxMessage> picked = new ArrayList<>();
        List<AiMessage> prior = priorAsc == null ? List.of() : priorAsc;
        for (int i = prior.size() - 1; i >= 0; i--) {
            String content = prior.get(i).getContent();
            int cost = tokenCounter.count(content);
            if (used + cost > budget) {
                break;
            }
            used += cost;
            picked.add(0, new CtxMessage(prior.get(i).getRole(), content));
        }
        // 截断可能把某一对问答撕开，留下半截 assistant 开头；
        // 连续多条开头也要一并丢掉，否则模型会以为对话由它起头
        while (!picked.isEmpty() && AiMessage.ROLE_ASSISTANT.equals(picked.get(0).role())) {
            picked.remove(0);
        }
        picked.add(new CtxMessage(AiMessage.ROLE_USER, latest));
        return picked;
    }

    /**
     * 统计一组上下文消息的 token 数，供日志与测试使用。
     */
    public int totalTokens(List<CtxMessage> messages) {
        int sum = 0;
        for (CtxMessage m : messages) {
            sum += tokenCounter.count(m.content());
        }
        return sum;
    }
}
