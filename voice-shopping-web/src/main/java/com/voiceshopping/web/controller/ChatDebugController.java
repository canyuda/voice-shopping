package com.voiceshopping.web.controller;

import com.voiceshopping.business.orchestrator.OrchestratorService;
import com.voiceshopping.business.session.SessionService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.web.dto.ChatDebugReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint that drives {@link OrchestratorService} synchronously over HTTP.
 * Useful for end-to-end tests without ASR/TTS round trips.
 * <p>
 * Auto-creates the underlying business session via {@link SessionService} when
 * absent — the orchestrator itself is find-only by contract, so the controller
 * has to bootstrap.
 */
@RestController
public class ChatDebugController {

    private static final String DEFAULT_CHANNEL = "HOME_ENTRY";

    private final OrchestratorService orchestratorService;
    private final SessionService sessionService;

    public ChatDebugController(OrchestratorService orchestratorService,
                               SessionService sessionService) {
        this.orchestratorService = orchestratorService;
        this.sessionService = sessionService;
    }

    @PostMapping("/api/v1/chat")
    public ApiResult<EmotionResult> chat(@Valid @RequestBody ChatDebugReq req) {
        sessionService.getOrCreate(req.sessionId(), null, req.userId(), DEFAULT_CHANNEL);
        EmotionResult reply = orchestratorService.handle(req.sessionId(), req.userId(), req.utterance());
        return ApiResult.ok(reply);
    }
}
