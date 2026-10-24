package org.leon.aimodule.chat.stream;

/** 上游返回非 2xx。status 给编排层判 retryable，不在网关里猜。 */
public class UpstreamStatusException extends RuntimeException {

    private final int status;

    public UpstreamStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
