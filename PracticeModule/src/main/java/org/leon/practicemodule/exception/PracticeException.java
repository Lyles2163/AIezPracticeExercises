package org.leon.practicemodule.exception;

/**
 * 刷题子系统的业务异常。只带一句给用户看的 msg，内部细节走日志。
 */
public class PracticeException extends RuntimeException {

    private final PracticeErrorCode errorCode;

    public PracticeException(PracticeErrorCode errorCode, String msg) {
        super(msg);
        this.errorCode = errorCode;
    }

    public PracticeErrorCode errorCode() {
        return errorCode;
    }
}
