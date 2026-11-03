package org.leon.aimodule.chat.exception;

/**
 * OpenAI 兼容链的拒绝：带 HTTP 状态码与协议内的 code。
 *
 * 不能复用 {@link ChatRejectException}：那条 advice 按仓库约定一律回 HTTP 200 + Result(code=0)，
 * 而桌宠那侧是官方 SDK，只认 {@code {"error":{...}}} 且要靠 4xx 才把失败当异常抛。
 */
public class OpenAiRejectException extends RuntimeException {

    private final int status;
    private final String code;

    public OpenAiRejectException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static OpenAiRejectException badRequest(String code, String message) {
        return new OpenAiRejectException(400, code, message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
