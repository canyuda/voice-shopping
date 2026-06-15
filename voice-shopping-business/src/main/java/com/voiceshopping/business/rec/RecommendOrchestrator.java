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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the full recommendation pipeline:
 * load profile → build query → embed → resolve scope → filter → retrieve candidates →
 * fallback → rerank → top 3 → generate reasons → return result.
 * <p>
 * Query construction, filter build, and progressive fallback are delegated to
 * {@link RecommendCandidateRetriever}. Multi-tenant isolation is enforced here:
 * the orchestrator resolves the {@link SessionScope} via {@link SessionScopeCache}
 * (cache miss → platform-wide fallback with WARN log) and builds a filter
 * strategy that re-merges the scope fragment on every fallback retry, so
 * relaxed retries cannot leak past merchant boundaries.
 */
@Service
public class RecommendOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecommendOrchestrator.class);
    private static final int FINAL_TOP_K = 3;

    private final UserProfileService profileService;
    private final EmbeddingService embeddingService;
    private final RecommendCandidateRetriever retriever;
    private final ProfileReranker reranker;
    private final RecommendReasonService reasonService;
    private final SessionScopeCache scopeCache;
    private final ScopeFilterBuilder scopeFilterBuilder;
    private final SqlFilterBuilder sqlFilterBuilder;

    public RecommendOrchestrator(UserProfileService profileService,
                                 EmbeddingService embeddingService,
                                 RecommendCandidateRetriever retriever,
                                 ProfileReranker reranker,
                                 RecommendReasonService reasonService,
                                 SessionScopeCache scopeCache,
                                 ScopeFilterBuilder scopeFilterBuilder,
                                 SqlFilterBuilder sqlFilterBuilder) {
        this.profileService = profileService;
        this.embeddingService = embeddingService;
        this.retriever = retriever;
        this.reranker = reranker;
        this.reasonService = reasonService;
        this.scopeCache = scopeCache;
        this.scopeFilterBuilder = scopeFilterBuilder;
        this.sqlFilterBuilder = sqlFilterBuilder;
    }

    /**
     * Execute the full recommendation pipeline.
     */
    public RecommendResult recommend(String sessionId,
                                     Long userId,
                                     String utterance,
                                     Map<String, Object> slots) {
        log.info("Recommendation request: sessionId={}, userId={}, utterance={}, slots={}",
                sessionId, userId, utterance, slots);

        // 1. Load user profile
        UserProfileSnapshot profile = userId != null ? profileService.load(userId) : null;

        // 2. Build query from slots keywords only
        String query = retriever.buildQuery(utterance, slots);
        log.debug("Embedding query: {}", query);

        // 3. Generate query vector (reusable across fallback retries)
        float[] queryVector = embeddingService.embed(query);

        // 4. Resolve session scope (cache miss → platform-wide fallback)
        SessionScope scope = resolveScope(sessionId, userId);
        Filter scopeFilter = scopeFilterBuilder.build(scope);

        // 5. Compose a filter strategy that re-merges scope on every fallback retry
        Function<Map<String, Object>, Filter> filterFn =
                s -> sqlFilterBuilder.merge(retriever.buildFilter(s), scopeFilter);

        // 6. Retrieve candidates (with progressive fallback inside retriever)
        List<RecommendedItem> candidates = retriever.retrieve(queryVector, slots, filterFn);

        // 7. Empty result after all fallbacks
        if (candidates.isEmpty()) {
            log.info("No candidates found after all fallbacks");
            return RecommendResult.EMPTY;
        }

        // 8. Rerank by profile signals
        List<RecommendedItem> reranked = reranker.rerank(candidates, profile, slots);

        // 9. Take top K
        List<RecommendedItem> topK = reranked.stream()
                .limit(FINAL_TOP_K)
                .collect(Collectors.toList());

        // 10. Generate reasons via LLM
        String userNeeds = utterance + "; 槽位：" + slots;
        List<RecommendedItem> withReasons = reasonService.attachReasons(sessionId, userNeeds, topK);

        // 11. Return result
        return new RecommendResult(withReasons, "professional");
    }

    /**
     * Look up scope from cache; on miss, log WARN and fall back to platform-wide.
     * Never throws — see merchant-data-isolation Decision 3.
     */
    private SessionScope resolveScope(String sessionId, Long userId) {
        if (sessionId == null) {
            return SessionScope.platformWide(userId);
        }
        return scopeCache.get(sessionId).orElseGet(() -> {
            log.warn("Scope cache miss for sessionId={}, falling back to platform-wide", sessionId);
            return SessionScope.platformWide(userId);
        });
    }
}
