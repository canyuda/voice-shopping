package com.voiceshopping.web.controller;

import com.voiceshopping.business.memory.LongTermMemoryWriter;
import com.voiceshopping.business.session.SessionService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.web.dto.MemoryFlushRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug entry point for the long-term memory writeback. Manual trigger only —
 * production triggers (WebSocket close / order confirm / TTL listener) come in a
 * follow-up change once idempotency is wired in.
 */
@RestController
@RequestMapping("/api/v1/debug/memory")
public class MemoryDebugController {

    private static final Logger log = LoggerFactory.getLogger(MemoryDebugController.class);

    private final LongTermMemoryWriter longTermMemoryWriter;
    private final SessionService sessionService;

    public MemoryDebugController(LongTermMemoryWriter longTermMemoryWriter,
                                 SessionService sessionService) {
        this.longTermMemoryWriter = longTermMemoryWriter;
        this.sessionService = sessionService;
    }

    /**
     * Manually trigger long-term memory flush for a session.
     * <p>
     * If {@code userId} is omitted, it is resolved from the session row
     * (404 NotFound if the session doesn't exist).
     */
    @PostMapping("/flush")
    public ApiResult<String> flush(@RequestBody MemoryFlushRequest req) {
        if (req == null || req.sessionId() == null || req.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Long userId = req.userId() != null ? req.userId() : sessionService.findUserId(req.sessionId());
        log.info("Manual memory flush requested: sessionId={}, userId={}", req.sessionId(), userId);
        longTermMemoryWriter.flushOnSessionEnd(req.sessionId(), userId);
        return ApiResult.ok("ok");
    }
}
