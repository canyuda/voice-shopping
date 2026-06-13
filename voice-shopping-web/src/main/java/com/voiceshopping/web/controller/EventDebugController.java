package com.voiceshopping.web.controller;

import com.voiceshopping.business.event.VoiceEventPublisher;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.event.UserSpokenEvent;
import com.voiceshopping.web.dto.UserSpokenEventReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint to trigger {@link UserSpokenEvent} on demand.
 * Useful for verifying async listener wiring without driving the full ASR
 * pipeline.
 */
@RestController
@RequestMapping("/api/v1/event")
public class EventDebugController {

    private final VoiceEventPublisher publisher;

    public EventDebugController(VoiceEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/user-spoken")
    public ApiResult<Void> userSpoken(@Valid @RequestBody UserSpokenEventReq req) {
        UserSpokenEvent event = new UserSpokenEvent(
                req.sessionId(), req.userId(), req.utterance(), System.currentTimeMillis());
        publisher.publish(event);
        return ApiResult.ok();
    }
}
