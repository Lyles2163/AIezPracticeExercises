package org.leon.aimodule.imagegen.exception;

/** 生图链路的可展示错误：消息直接给用户看，原始异常只进日志。 */
public class ImageGenException extends RuntimeException {

    public ImageGenException(String message) {
        super(message);
    }

    public ImageGenException(String message, Throwable cause) {
        super(message, cause);
    }
}
