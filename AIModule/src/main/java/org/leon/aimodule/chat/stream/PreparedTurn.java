package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.leon.aimodule.rag.qa.RagContextService;

import java.util.List;

public record PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                           List<CtxMessage> messages, ModelParams params, String userName, String effortName,
                           boolean useRag, RagContextService.RagOptions ragOptions,
                           /** 本轮的 system prompt 覆盖；与 RAG 资料块的拼接顺序见 ChatStreamService#run。 */
                           String systemPromptOverride,
                           /** 个人供应商的上游路由；内置模型为 null，此时走服务端配置。 */
                           ProviderRoute route) {

    /** 无 system prompt 覆盖、无个人路由的原九参构造。 */
    public PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                        List<CtxMessage> messages, ModelParams params, String userName, String effortName,
                        boolean useRag, RagContextService.RagOptions ragOptions) {
        this(conversationId, userMessageId, assistantMessageId, messages, params, userName, effortName,
                useRag, ragOptions, null, null);
    }

    /** 有 system prompt 覆盖、无个人路由的十参构造。 */
    public PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                        List<CtxMessage> messages, ModelParams params, String userName, String effortName,
                        boolean useRag, RagContextService.RagOptions ragOptions, String systemPromptOverride) {
        this(conversationId, userMessageId, assistantMessageId, messages, params, userName, effortName,
                useRag, ragOptions, systemPromptOverride, null);
    }
}
