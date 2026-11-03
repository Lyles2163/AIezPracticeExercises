package org.leon.aimodule.chat.stream;

import org.leon.aimodule.rag.qa.RagContextService;

import java.util.List;

public record StreamCommand(Long conversationId, String content, String modelKey, String effort,
                            String clientMsgId, boolean regenerate,
                            boolean useRag, List<String> ragScope, Long ragSubjectId, Integer ragTopK,
                            /** 本轮的 system prompt 覆盖（桌宠的人设走这条；网页端传 null）。 */
                            String systemPromptOverride) {

    /** 没有 system prompt 覆盖时的原十参构造，网页端与既有测试都走这条。 */
    public StreamCommand(Long conversationId, String content, String modelKey, String effort,
                         String clientMsgId, boolean regenerate,
                         boolean useRag, List<String> ragScope, Long ragSubjectId, Integer ragTopK) {
        this(conversationId, content, modelKey, effort, clientMsgId, regenerate,
                useRag, ragScope, ragSubjectId, ragTopK, null);
    }

    public RagContextService.RagOptions toRagOptions() {
        return new RagContextService.RagOptions(ragScope, ragSubjectId, ragTopK);
    }
}
