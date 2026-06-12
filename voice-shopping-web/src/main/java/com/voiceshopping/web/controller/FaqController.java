package com.voiceshopping.web.controller;

import com.voiceshopping.infrastructure.vector.EmbeddingService;
import com.voiceshopping.infrastructure.vector.FaqSearchResult;
import com.voiceshopping.infrastructure.vector.FaqVectorService;
import com.voiceshopping.web.dto.FaqAskDebugRequest;
import com.voiceshopping.web.dto.FaqAskDebugResponse;
import com.voiceshopping.web.dto.FaqAskRequest;
import com.voiceshopping.web.dto.FaqAskResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * FAQ question-answering endpoints.
 */
@RestController
@RequestMapping("/api/v1/faq")
public class FaqController {

    private final EmbeddingService embeddingService;
    private final FaqVectorService faqVectorService;

    public FaqController(EmbeddingService embeddingService,
                         FaqVectorService faqVectorService) {
        this.embeddingService = embeddingService;
        this.faqVectorService = faqVectorService;
    }

    /**
     * Find the best matching FAQ answer. Similarity threshold 0.75.
     */
    @PostMapping("/ask")
    public ResponseEntity<FaqAskResponse> ask(@RequestBody FaqAskRequest request) {
        long merchantId = request.merchantId() != null ? request.merchantId() : 0L;
        float[] queryVector = embeddingService.embed(request.question());

        Optional<FaqSearchResult> result =
                faqVectorService.searchBest(merchantId, queryVector);

        if (result.isPresent()) {
            FaqSearchResult r = result.get();
            return ResponseEntity.ok(new FaqAskResponse(
                    true,
                    r.id(),
                    r.question(),
                    r.answer(),
                    r.category() != null ? r.category() : "",
                    r.similarity()
            ));
        }

        return ResponseEntity.ok(FaqAskResponse.notFound());
    }

    /**
     * Debug endpoint: return top-N FAQ candidates with similarity scores,
     * bypassing the 0.75 threshold. Useful for operations annotation.
     */
    @PostMapping("/ask-debug")
    public ResponseEntity<FaqAskDebugResponse> askDebug(@RequestBody FaqAskDebugRequest request) {
        float[] queryVector = embeddingService.embed(request.question());

        var results = faqVectorService.searchTopN(
                request.resolveMerchantId(), queryVector, request.resolveTopN());

        var items = results.stream()
                .map(r -> new FaqAskDebugResponse.FaqCandidate(
                        r.id(),
                        r.question(),
                        r.answer(),
                        r.category() != null ? r.category() : "",
                        r.similarity()))
                .toList();

        return ResponseEntity.ok(new FaqAskDebugResponse(items));
    }
}
