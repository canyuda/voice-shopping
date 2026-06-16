package com.voiceshopping.business.rec;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.business.scope.SessionScopeCache;
import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import com.voiceshopping.common.dto.session.SessionScope;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import com.voiceshopping.infrastructure.vector.ScopeFilterBuilder;
import com.voiceshopping.infrastructure.vector.SqlFilterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Equivalence tests: same inputs MUST produce equivalent outputs from
 * {@link RecommendOrchestrator} and {@link ParallelRecommendService}.
 * Reasons are now deferred to the merged emotion prompt, so both
 * implementations return items with reason=null directly.
 */
class ParallelRecommendEquivalenceTest {

    private UserProfileService profileService;
    private EmbeddingService embeddingService;
    private RecommendCandidateRetriever retriever;
    private ProfileReranker reranker;
    private SessionScopeCache scopeCache;
    private ScopeFilterBuilder scopeFilterBuilder;
    private SqlFilterBuilder sqlFilterBuilder;

    private RecommendOrchestrator serial;
    private ParallelRecommendService parallel;

    @BeforeEach
    void setUp() {
        profileService = mock(UserProfileService.class);
        embeddingService = mock(EmbeddingService.class);
        retriever = mock(RecommendCandidateRetriever.class);
        reranker = mock(ProfileReranker.class);
scopeCache = mock(SessionScopeCache.class);
        scopeFilterBuilder = mock(ScopeFilterBuilder.class);
        sqlFilterBuilder = mock(SqlFilterBuilder.class);

        // Default: scope cache miss (platform-wide fallback path) and a no-op
        // merge that returns the generic filter unchanged. Tests that need a
        // different scope override these stubs.
        when(scopeCache.get(anyString())).thenReturn(Optional.empty());
        when(scopeFilterBuilder.build(any())).thenReturn(Filter.EMPTY);
        when(sqlFilterBuilder.merge(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        serial = new RecommendOrchestrator(profileService, embeddingService, retriever,
                reranker, scopeCache, scopeFilterBuilder, sqlFilterBuilder);
        parallel = new ParallelRecommendService(profileService, embeddingService, retriever,
                reranker, scopeCache, scopeFilterBuilder, sqlFilterBuilder);
    }

    private RecommendedItem item(long id, String name, double price) {
        return new RecommendedItem(id, name, BigDecimal.valueOf(price), "", 0.9, Map.of());
    }

    @Test
    @DisplayName("Happy path: items / order / explanationTone equivalent across serial and parallel")
    void equivalent_happyPath() {
        UserProfileSnapshot profile = mock(UserProfileSnapshot.class);
        when(profileService.load(1L)).thenReturn(profile);

        float[] vec = new float[]{0.1f, 0.2f};
        when(embeddingService.embed(anyString())).thenReturn(vec);

        Filter filter = new Filter("price > 0", List.of());
        when(retriever.buildQuery(anyString(), any())).thenReturn("跑鞋");
        when(retriever.buildFilter(any())).thenReturn(filter);

        List<RecommendedItem> candidates = List.of(item(1, "A", 100), item(2, "B", 200), item(3, "C", 300), item(4, "D", 400));
        when(retriever.retrieve(any(), any(), any())).thenReturn(candidates);

        // rerank returns the candidates in a deterministic order (reverse for distinctness)
        List<RecommendedItem> reranked = List.of(item(3, "C", 300), item(2, "B", 200), item(1, "A", 100), item(4, "D", 400));
        when(reranker.rerank(eq(candidates), any(), any())).thenReturn(reranked);

        Map<String, Object> slots = Map.of("category", "跑鞋", "budget", 500);

        RecommendResult serialResult = serial.recommend("s1", 1L, "买跑鞋", slots);
        RecommendResult parallelResult = parallel.recommend("s1", 1L, "买跑鞋", slots);

        // Equivalence: same productIds, same order, same tone
        assertEquals(extractIds(serialResult), extractIds(parallelResult), "items productId list must match (order included)");
        assertEquals(serialResult.explanationTone(), parallelResult.explanationTone());
        assertEquals(3, serialResult.items().size(), "top K = 3");
        assertEquals(List.of(3L, 2L, 1L), extractIds(serialResult));
    }

    @Test
    @DisplayName("All fallbacks empty: both return RecommendResult.EMPTY")
    void equivalent_allEmpty() {
        when(profileService.load(anyLong())).thenReturn(null);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(retriever.buildQuery(anyString(), any())).thenReturn("x");
        when(retriever.buildFilter(any())).thenReturn(new Filter("1=1", List.of()));
        when(retriever.retrieve(any(), any(), any())).thenReturn(Collections.emptyList());

        RecommendResult s = serial.recommend("s1", 1L, "x", Map.of());
        RecommendResult p = parallel.recommend("s1", 1L, "x", Map.of());

        assertSame(RecommendResult.EMPTY, s);
        assertSame(RecommendResult.EMPTY, p);
        verify(reranker, never()).rerank(any(), any(), any());
    }

    @Test
    @DisplayName("userId == null: profile.load is NOT called by parallel either")
    void parallel_userIdNull_skipsProfileLoad() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(retriever.buildQuery(anyString(), any())).thenReturn("x");
        when(retriever.buildFilter(any())).thenReturn(new Filter("1=1", List.of()));
        when(retriever.retrieve(any(), any(), any())).thenReturn(Collections.emptyList());

        parallel.recommend("s1", null, "x", Map.of());

        verify(profileService, never()).load(anyLong());
    }

    @Test
    @DisplayName("Profile leg failure: parallel propagates as IllegalStateException (fail-fast)")
    void parallel_profileFailsFast() {
        when(profileService.load(anyLong())).thenThrow(new RuntimeException("DB down"));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(retriever.buildQuery(anyString(), any())).thenReturn("x");
        when(retriever.buildFilter(any())).thenReturn(new Filter("1=1", List.of()));
        when(retriever.retrieve(any(), any(), any())).thenReturn(Collections.emptyList());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parallel.recommend("s1", 1L, "x", Map.of()));
        // Original cause must be preserved
        Throwable root = ex.getCause();
        assertEquals("DB down", root != null ? root.getMessage() : null);
    }

    @Test
    @DisplayName("Candidates leg failure: parallel propagates as IllegalStateException (fail-fast)")
    void parallel_candidatesFailsFast() {
        when(profileService.load(anyLong())).thenReturn(null);
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("Embed timeout"));
        when(retriever.buildQuery(anyString(), any())).thenReturn("x");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parallel.recommend("s1", 1L, "x", Map.of()));
        Throwable root = ex.getCause();
        assertEquals("Embed timeout", root != null ? root.getMessage() : null);
    }

    @Test
    @DisplayName("Both legs invoked exactly once per recommend call")
    void parallel_bothLegsInvokedOnce() {
        when(profileService.load(anyLong())).thenReturn(null);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(retriever.buildQuery(anyString(), any())).thenReturn("x");
        when(retriever.buildFilter(any())).thenReturn(new Filter("1=1", List.of()));
        when(retriever.retrieve(any(), any(), any())).thenReturn(Collections.emptyList());

        parallel.recommend("s1", 1L, "x", Map.of());

        verify(profileService, times(1)).load(1L);
        verify(embeddingService, times(1)).embed("x");
        verify(retriever, times(1)).retrieve(any(), any(), any());
    }

    @Test
    @DisplayName("Scope cache hit: scopeFilterBuilder is invoked with the cached scope")
    void scope_cacheHit_appliesScopeFilter() {
        SessionScope scope = new SessionScope(7L, List.of(5L), null);
        when(scopeCache.get("sess-merch")).thenReturn(Optional.of(scope));
        when(profileService.load(anyLong())).thenReturn(null);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(retriever.buildQuery(anyString(), any())).thenReturn("x");
        when(retriever.buildFilter(any())).thenReturn(new Filter("1=1", List.of()));
        when(retriever.retrieve(any(), any(), any())).thenReturn(Collections.emptyList());

        serial.recommend("sess-merch", 7L, "x", Map.of());
        parallel.recommend("sess-merch", 7L, "x", Map.of());

        // Both code paths must consult ScopeFilterBuilder with the cached scope.
        verify(scopeFilterBuilder, times(2)).build(scope);
    }

    private static List<Long> extractIds(RecommendResult r) {
        return r.items().stream().map(RecommendedItem::productId).toList();
    }
}
