package com.voiceshopping.web.controller;

import com.voiceshopping.business.scope.SessionScopeCache;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.session.SessionScope;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import com.voiceshopping.infrastructure.vector.ProductVectorService;
import com.voiceshopping.infrastructure.vector.ScopeFilterBuilder;
import com.voiceshopping.infrastructure.vector.SqlFilterBuilder;
import com.voiceshopping.web.dto.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smoke test endpoint for product vector search.
 * <p>
 * When {@code sessionId} is supplied, the request is scoped via
 * {@link SessionScopeCache}; otherwise the legacy unscoped behavior is
 * preserved (useful for ad-hoc dev queries without an active session).
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final EmbeddingService embeddingService;
    private final ProductVectorService productVectorService;
    private final SqlFilterBuilder sqlFilterBuilder;
    private final SessionScopeCache scopeCache;
    private final ScopeFilterBuilder scopeFilterBuilder;

    public SearchController(EmbeddingService embeddingService,
                            ProductVectorService productVectorService,
                            SqlFilterBuilder sqlFilterBuilder,
                            SessionScopeCache scopeCache,
                            ScopeFilterBuilder scopeFilterBuilder) {
        this.embeddingService = embeddingService;
        this.productVectorService = productVectorService;
        this.sqlFilterBuilder = sqlFilterBuilder;
        this.scopeCache = scopeCache;
        this.scopeFilterBuilder = scopeFilterBuilder;
    }

    @GetMapping("/search")
    public ApiResult<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String sessionId) {

        float[] queryVector = embeddingService.embed(q);

        // Build filter from request params
        Map<String, Object> slots = new LinkedHashMap<>();
        if (maxPrice != null) {
            slots.put("budget", maxPrice);
        }
        if (attributes != null && !attributes.isBlank()) {
            parseAttributesInto(attributes, slots);
        }

        Filter slotFilter = sqlFilterBuilder.fromSlots(slots);
        Filter scopeFilter = resolveScopeFilter(sessionId);
        Filter filter = sqlFilterBuilder.merge(slotFilter, scopeFilter);

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
     * Resolve scope filter for this request:
     * <ul>
     *   <li>{@code sessionId} blank → no scope (legacy smoke-test path).</li>
     *   <li>cache hit → scope filter from cached SessionScope.</li>
     *   <li>cache miss → WARN + platform-wide fallback (no scope filter).
     *       The {@code SearchController} cannot reliably reconstruct userId
     *       here, so fallback uses {@code (null, [], null)} — see
     *       merchant-data-isolation tasks.md 14.2.</li>
     * </ul>
     */
    private Filter resolveScopeFilter(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Filter.EMPTY;
        }
        SessionScope scope = scopeCache.get(sessionId).orElseGet(() -> {
            log.warn("Scope cache miss for sessionId={}, falling back to platform-wide", sessionId);
            return SessionScope.platformWide(null);
        });
        return scopeFilterBuilder.build(scope);
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
