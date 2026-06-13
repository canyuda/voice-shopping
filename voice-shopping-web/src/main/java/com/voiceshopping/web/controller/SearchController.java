package com.voiceshopping.web.controller;

import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import com.voiceshopping.infrastructure.vector.ProductVectorService;
import com.voiceshopping.infrastructure.vector.SqlFilterBuilder;
import com.voiceshopping.web.dto.SearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smoke test endpoint for product vector search.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final EmbeddingService embeddingService;
    private final ProductVectorService productVectorService;
    private final SqlFilterBuilder sqlFilterBuilder;

    public SearchController(EmbeddingService embeddingService,
                            ProductVectorService productVectorService,
                            SqlFilterBuilder sqlFilterBuilder) {
        this.embeddingService = embeddingService;
        this.productVectorService = productVectorService;
        this.sqlFilterBuilder = sqlFilterBuilder;
    }

    @GetMapping("/search")
    public ApiResult<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String attributes) {

        float[] queryVector = embeddingService.embed(q);

        // Build filter from request params
        Map<String, Object> slots = new LinkedHashMap<>();
        if (maxPrice != null) {
            slots.put("budget", maxPrice);
        }
        if (attributes != null && !attributes.isBlank()) {
            parseAttributesInto(attributes, slots);
        }

        Filter filter = sqlFilterBuilder.fromSlots(slots);

        var results = productVectorService.search(
                queryVector, filter.clause(), filter.params(), topK);

        var items = results.stream()
                .map(r -> new SearchResponse.SearchItem(
                        r.productId(),
                        r.name(),
                        r.categoryL1() != null ? r.categoryL1() : "",
                        r.categoryL2() != null ? r.categoryL2() : "",
                        r.price(),
                        r.similarity()))
                .toList();

        return ApiResult.ok(new SearchResponse(q, items.size(), items));
    }

    /**
     * Parse JSON attributes string and merge into slots map.
     */
    @SuppressWarnings("unchecked")
    private void parseAttributesInto(String attributes, Map<String, Object> slots) {
        try {
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(attributes, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            slots.putAll(parsed);
        } catch (Exception e) {
            // ignore malformed JSON
        }
    }
}
