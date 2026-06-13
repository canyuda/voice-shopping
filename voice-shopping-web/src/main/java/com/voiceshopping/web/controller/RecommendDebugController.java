package com.voiceshopping.web.controller;

import com.voiceshopping.business.rec.ParallelRecommendService;
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
 * Debug endpoints for the recommendation pipeline.
 * <p>
 * Two flavors:
 * <ul>
 *   <li>{@code POST /api/v1/agent/recommend} — sequential {@link RecommendOrchestrator}.</li>
 *   <li>{@code POST /api/v1/agent/recommend/parallel} — parallel {@link ParallelRecommendService}
 *       (profile load and candidate retrieval fanned out via CompletableFuture).</li>
 * </ul>
 * Both endpoints share the same request shape and return identical
 * {@link RecommendResult} structures — useful for side-by-side equivalence
 * checks in real environments.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class RecommendDebugController {

    private final RecommendOrchestrator orchestrator;
    private final ParallelRecommendService parallelService;

    public RecommendDebugController(RecommendOrchestrator orchestrator,
                                    ParallelRecommendService parallelService) {
        this.orchestrator = orchestrator;
        this.parallelService = parallelService;
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

    @PostMapping("/recommend/parallel")
    public ApiResult<RecommendResult> recommendParallel(
            @RequestParam String sessionId,
            @RequestParam Long userId,
            @Valid @RequestBody RecommendDebugReq req) {

        Map<String, Object> slots = req.slots() != null ? req.slots() : Map.of();
        RecommendResult result = parallelService.recommend(sessionId, userId, req.utterance(), slots);
        return ApiResult.ok(result);
    }
}
