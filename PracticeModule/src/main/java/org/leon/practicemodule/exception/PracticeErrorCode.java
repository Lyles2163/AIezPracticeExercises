package org.leon.practicemodule.exception;

/**
 * 业务子码。走 Result.error(int,String)，HTTP 状态仍是 200——
 * 前端 request.ts 以 code!==1 判错，未鉴权由网关直接回 HTTP 401，不经过这里。
 */
public enum PracticeErrorCode {
    BAD_REQUEST(40000),
    IDENTITY_MISSING(40100),
    FORBIDDEN(40300),
    NOT_FOUND(40400),
    CONFLICT(40900),
    EXAM_EXPIRED(41001),
    EXAM_SUBMITTED(41002);

    private final int code;

    PracticeErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
