package com.voiceshopping.infrastructure.vector;

/**
 * Exception thrown when DashScope embedding API call fails after all retries.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
