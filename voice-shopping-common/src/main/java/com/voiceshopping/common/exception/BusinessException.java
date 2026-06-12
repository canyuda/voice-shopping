package com.voiceshopping.common.exception;

/**
 * General business logic exception with an HTTP status code hint.
 */
public class BusinessException extends RuntimeException {

    private final int statusCode;

    public BusinessException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public BusinessException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
