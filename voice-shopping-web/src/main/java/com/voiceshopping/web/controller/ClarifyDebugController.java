package com.voiceshopping.web.controller;

import com.voiceshopping.business.agent.ClarifyService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.ClarifyDebugReq;
import com.voiceshopping.common.dto.agent.ClarifyResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint for testing clarify decisions.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class ClarifyDebugController {

    private final ClarifyService clarifyService;

    public ClarifyDebugController(ClarifyService clarifyService) {
        this.clarifyService = clarifyService;
    }

    @PostMapping("/clarify")
    public ApiResult<ClarifyResult> clarify(@RequestBody ClarifyDebugReq request) {
        ClarifyResult result = clarifyService.decide(
                request.sessionId(),
                request.utterance(),
                request.slots()
        );
        return ApiResult.ok(result);
    }
}
