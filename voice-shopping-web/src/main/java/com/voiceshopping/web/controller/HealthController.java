package com.voiceshopping.web.controller;

import com.voiceshopping.common.dto.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/ping")
    public ApiResult<Map<String, Object>> ping() {
        return ApiResult.ok(Map.of(
                "app", "voice-shopping",
                "status", "ok",
                "ts", Instant.now().toEpochMilli()
        ));
    }
}
