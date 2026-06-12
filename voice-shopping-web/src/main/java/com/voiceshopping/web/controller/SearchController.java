package com.voiceshopping.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import com.voiceshopping.infrastructure.vector.ProductVectorService;
import com.voiceshopping.web.dto.SearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Smoke test endpoint for product vector search.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final EmbeddingService embeddingService;
    private final ProductVectorService productVectorService;
    private final ObjectMapper objectMapper;

    public SearchController(EmbeddingService embeddingService,
                            ProductVectorService productVectorService,
                            ObjectMapper objectMapper) {
        this.embeddingService = embeddingService;
        this.productVectorService = productVectorService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/search")
    public ApiResult<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "1") long merchantId,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String attributes) {

        float[] queryVector = embeddingService.embed(q);
        Map<String, Object> attributeFilters = parseAttributes(attributes);

        var results = productVectorService.search(
                merchantId, queryVector, topK, minPrice, maxPrice, attributeFilters);

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

    private Map<String, Object> parseAttributes(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(attributes, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
