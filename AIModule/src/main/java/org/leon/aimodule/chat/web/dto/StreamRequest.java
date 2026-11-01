package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StreamRequest(@NotNull(message = "conversationId 不能为空") Long conversationId,
                            @Size(max = 32000, message = "单条消息过长") String content,
                            String modelKey, String effort,
                            @Size(max = 40, message = "clientMsgId 最长 40 字符") String clientMsgId,
                            boolean regenerate,
                            /* ===== RAG 相关字段 ===== */
                            /** 是否启用知识库增强。缺省 true。 */
                            Boolean useRag,
                            /** official / personal；缺省两者都查 */
                            List<String> ragScope,
                            /** null=不限学科；0=只看全学科通用；>0=本学科 OR 通用 */
                            Long ragSubjectId,
                            /** 检索条数，1~20，缺省走 rag.search.top-k-default */
                            Integer ragTopK) {
}
