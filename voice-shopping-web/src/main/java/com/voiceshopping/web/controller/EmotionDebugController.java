package com.voiceshopping.web.controller;

import com.voiceshopping.business.agent.EmotionService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.web.dto.EmotionDebugReq;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint for the emotion agent.
 * Allows isolated testing of the recommendation-to-speech wrapping step.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class EmotionDebugController {

    private final EmotionService emotionService;

    public EmotionDebugController(EmotionService emotionService) {
        this.emotionService = emotionService;
    }

    @PostMapping("/emotion")
    public ApiResult<EmotionResult> emotion(
            @RequestParam String sessionId,
            @RequestBody EmotionDebugReq req) {

        if (req.rec() == null) {
            throw new IllegalArgumentException("rec must not be null");
        }
        EmotionResult result = emotionService.wrap(sessionId, req.utterance(), req.userNeeds(), req.rec());
        return ApiResult.ok(result);
    }
}
