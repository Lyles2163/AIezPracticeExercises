package org.leon.loggingmodule.exception;

/**
 * 日志模块内部异常。仅在模块内部捕获并记录指标/降级，绝不向上抛出影响业务主链路。
 */
public class AuditLoggingException extends RuntimeException {

    public AuditLoggingException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuditLoggingException(String message) {
        super(message);
    }
}
