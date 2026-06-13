package com.voiceshopping.web.controller;

import com.voiceshopping.business.perspective.PerspectiveHubService;
import com.voiceshopping.business.rec.RecommendOrchestrator;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.web.dto.PerspectiveHubReq;
import com.voiceshopping.web.dto.PerspectiveHubResp;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Debug endpoint that combines the main recommendation pipeline with the
 * side-channel perspective discussion.
 * <p>
 * Wires {@link RecommendOrchestrator} (the stable serial implementation) to
 * keep this endpoint's regression surface focused on the perspective addition.
 */
@RestController
@RequestMapping("/api/v1/hub")
public class PerspectiveHubController {

    private final RecommendOrchestrator recommendOrchestrator;
    private final PerspectiveHubService perspectiveHub;

    public PerspectiveHubController(RecommendOrchestrator recommendOrchestrator,
                                    PerspectiveHubService perspectiveHub) {
        this.recommendOrchestrator = recommendOrchestrator;
        this.perspectiveHub = perspectiveHub;
    }

    @PostMapping("/perspective")
    public ApiResult<PerspectiveHubResp> perspective(@Valid @RequestBody PerspectiveHubReq req) {
        Map<String, Object> slots = req.slots() != null ? req.slots() : Map.of();
        RecommendResult rec = recommendOrchestrator.recommend(
                req.sessionId(), req.userId(), req.utterance(), slots);
        String text = perspectiveHub.discuss(req.sessionId(), req.utterance(), rec.items());
        return ApiResult.ok(new PerspectiveHubResp(text, rec));
    }
}
