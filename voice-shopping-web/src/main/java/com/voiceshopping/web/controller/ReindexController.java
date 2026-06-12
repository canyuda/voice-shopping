package com.voiceshopping.web.controller;

import com.voiceshopping.infrastructure.vector.ReindexResult;
import com.voiceshopping.infrastructure.vector.ReindexService;
import com.voiceshopping.web.dto.ReindexResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for triggering full reindex of product and FAQ embeddings.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class ReindexController {

    private final ReindexService reindexService;

    public ReindexController(ReindexService reindexService) {
        this.reindexService = reindexService;
    }

    /**
     * Trigger full product embedding reindex.
     */
    @PostMapping("/reindex")
    public ResponseEntity<ReindexResponse> reindexProducts() {
        ReindexResult result = reindexService.reindexProducts();
        return ResponseEntity.ok(toResponse(result));
    }

    /**
     * Trigger full FAQ embedding reindex.
     */
    @PostMapping("/faq-reindex")
    public ResponseEntity<ReindexResponse> reindexFaqs() {
        ReindexResult result = reindexService.reindexFaqs();
        return ResponseEntity.ok(toResponse(result));
    }

    private static ReindexResponse toResponse(ReindexResult r) {
        return new ReindexResponse(r.totalProcessed(), r.successCount(), r.failCount());
    }
}
