package com.voiceshopping.common.dto.agent;

import java.nio.ByteBuffer;

/**
 * Streaming chunk sent from backend to frontend via WebSocket.
 * <p>
 * Three frame types:
 * <ul>
 *   <li>TEXT — subtitle text segment for caption area</li>
 *   <li>AUDIO — PCM audio frame for speaker playback</li>
 *   <li>PRODUCTS — product card data for UI rendering</li>
 * </ul>
 */
public record StreamChunk(
        Type type,
        String text,
        ByteBuffer audio,
        Object products
) {
    public enum Type { TEXT, AUDIO, PRODUCTS }

    public static StreamChunk text(String t) {
        return new StreamChunk(Type.TEXT, t, null, null);
    }

    public static StreamChunk audio(ByteBuffer a) {
        return new StreamChunk(Type.AUDIO, null, a, null);
    }

    public static StreamChunk products(Object p) {
        return new StreamChunk(Type.PRODUCTS, null, null, p);
    }
}
