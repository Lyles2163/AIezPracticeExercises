package org.leon.aimodule.chat.stream;

import org.leon.aimodule.rag.qa.RagContextService;

import java.util.List;

public record StreamCommand(Long conversationId, String content, String modelKey, String effort,
                            String clientMsgId, boolean regenerate,
                            /** 本轮是否开启思考。null=跟随模型能力；true/false=用户显式开关。 */
                            Boolean thinking,
                            boolean useRag, List<String> ragScope, Long ragSubjectId, Integer ragTopK,
                            /** 本轮的 system prompt 覆盖（桌宠的人设走这条；网页端传 null）。 */
                            String systemPromptOverride) {

    /** 有思考开关、无 system prompt 覆盖的构造：网页端走这条。 */
    public StreamCommand(Long conversationId, String content, String modelKey, String effort,
                         String clientMsgId, boolean regenerate, Boolean thinking,
                         boolean useRag, List<String> ragScope, Long ragSubjectId, Integer ragTopK) {
        this(conversationId, content, modelKey, effort, clientMsgId, regenerate, thinking,
                useRag, ragScope, ragSubjectId, ragTopK, null);
    }

    /** 没有 system prompt 覆盖与思考开关时的构造，既有测试走这条。 */
    public StreamCommand(Long conversationId, String content, String modelKey, String effort,
                         String clientMsgId, boolean regenerate,
                         boolean useRag, List<String> ragScope, Long ragSubjectId, Integer ragTopK) {
        this(conversationId, content, modelKey, effort, clientMsgId, regenerate,
                null, useRag, ragScope, ragSubjectId, ragTopK, null);
    }

    public RagContextService.RagOptions toRagOptions() {
        return new RagContextService.RagOptions(ragScope, ragSubjectId, ragTopK);
    }
}
