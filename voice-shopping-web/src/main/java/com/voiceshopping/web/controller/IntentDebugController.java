package com.voiceshopping.web.controller;

import com.voiceshopping.business.agent.IntentService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.IntentRequest;
import com.voiceshopping.common.dto.agent.IntentResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint for testing intent classification.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class IntentDebugController {

    private final IntentService intentService;

    public IntentDebugController(IntentService intentService) {
        this.intentService = intentService;
    }

    @PostMapping("/intent")
    public ApiResult<IntentResult> classifyIntent(@RequestBody IntentRequest request) {
        IntentResult result = intentService.classify(request.sessionId(), request.utterance());
        return ApiResult.ok(result);
    }
}
