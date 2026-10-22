package org.leon.aimodule.rag.exception;

public class EmbeddingException extends RagException {
    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
