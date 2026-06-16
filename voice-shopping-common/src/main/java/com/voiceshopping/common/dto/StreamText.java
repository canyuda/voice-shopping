package com.voiceshopping.common.dto;

/**
 * Streaming text signal for caption/subtitle display.
 */
public record StreamText(
        String type,
        String text
) {
    public StreamText(String text) {
        this("stream_text", text);
    }
}
