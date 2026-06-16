package com.voiceshopping.common.dto;

/**
 * Signal indicating the end of a streaming response.
 */
public record StreamDone(
        String type
) {
    public StreamDone() {
        this("stream_done");
    }
}
