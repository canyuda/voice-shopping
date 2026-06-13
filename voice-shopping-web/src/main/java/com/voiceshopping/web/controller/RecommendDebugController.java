package com.voiceshopping.web.controller;

import com.voiceshopping.business.rec.RecommendOrchestrator;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.web.dto.RecommendDebugReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Debug endpoint for the recommendation pipeline.
 * Allows manual testing of the full recommend flow.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class RecommendDebugController {

    private final RecommendOrchestrator orchestrator;

    public RecommendDebugController(RecommendOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/recommend")
    public ApiResult<RecommendResult> recommend(
            @RequestParam String sessionId,
            @RequestParam Long userId,
            @Valid @RequestBody RecommendDebugReq req) {

        Map<String, Object> slots = req.slots() != null ? req.slots() : Map.of();
        RecommendResult result = orchestrator.recommend(sessionId, userId, req.utterance(), slots);
        return ApiResult.ok(result);
    }
}
