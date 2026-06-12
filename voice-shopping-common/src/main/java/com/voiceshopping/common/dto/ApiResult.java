package com.voiceshopping.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Unified API response wrapper for all endpoints.
 *
 * @param <T> payload type
 */
public record ApiResult<T>(
        int code,
        String msg,
        T data
) {

    private static final String SUCCESS_MSG = "success";

    // ---- Success factories ----

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(200, SUCCESS_MSG, data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(200, SUCCESS_MSG, null);
    }

    // ---- Error factories ----

    public static <T> ApiResult<T> error(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }

    public static <T> ApiResult<T> error(int code, String msg, T data) {
        return new ApiResult<>(code, msg, data);
    }
}
