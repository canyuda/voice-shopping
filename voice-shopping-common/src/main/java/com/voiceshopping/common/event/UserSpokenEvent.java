package com.voiceshopping.common.event;

/**
 * Cross-cutting event signaling that the user has spoken in a session.
 * <p>
 * Published on the Spring application bus so that orthogonal concerns
 * (cache warmup, audit logging, etc.) can react asynchronously without
 * coupling to the main pipeline.
 *
 * @param sessionId active session id; never null/blank
 * @param userId    user id; may be null for anonymous sessions
 * @param utterance the recognized user speech text; never null/blank
 * @param timestamp epoch millis when the event was constructed
 */
public record UserSpokenEvent(
        String sessionId,
        Long userId,
        String utterance,
        long timestamp
) {
}
