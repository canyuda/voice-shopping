package com.voiceshopping.common.exception;

/**
 * Thrown when access is denied (403 Forbidden).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
