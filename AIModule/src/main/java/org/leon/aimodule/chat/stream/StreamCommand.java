package org.leon.aimodule.chat.stream;

import org.leon.aimodule.rag.qa.RagContextService;

import java.util.List;

public record StreamCommand(Long conversationId, String content, String modelKey, String effort,
                            String clientMsgId, boolean regenerate,
                            boolean useRag, List<String> ragScope, Long ragSubjectId, int ragTopK) {

    public RagContextService.RagOptions toRagOptions() {
        return new RagContextService.RagOptions(ragScope, ragSubjectId, ragTopK);
    }
}
