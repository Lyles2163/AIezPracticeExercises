package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatModelGateway {

    Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params);

    /** 上游一帧的归一化视图，六个字段都可能为 null。 */
    record ChatChunk(String text, String reasoning, String finishReason,
                     Integer promptTokens, Integer completionTokens, Integer reasoningTokens) {
    }
}
