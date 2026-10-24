package org.leon.aimodule.chat.service;

/**
 * 组装后发给模型的一条上下文消息。
 *
 * @param role    角色，取 {@link org.leon.aimodule.chat.entity.AiMessage#ROLE_USER}
 *                或 {@link org.leon.aimodule.chat.entity.AiMessage#ROLE_ASSISTANT}
 * @param content 消息正文
 */
public record CtxMessage(String role, String content) {
}
