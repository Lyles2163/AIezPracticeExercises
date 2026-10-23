package org.leon.aimodule.chat.exception;

/**
 * 业务拒绝。advice 统一翻成 HTTP 200 + Result(code=0)，
 * 与仓库既有约定一致，不引入 4xx。
 */
public class ChatRejectException extends RuntimeException {

    public ChatRejectException(String message) {
        super(message);
    }
}
