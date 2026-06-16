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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Parallelized variant of {@link RecommendOrchestrator#recommend} that fans out
 * the profile-load leg and the candidate-retrieval leg concurrently using
 * {@link CompletableFuture}.
 * <p>
 * The two legs are independent:
 * <ul>
 *   <li><b>Profile leg</b>: depends only on userId.</li>
 *   <li><b>Candidates leg</b>: buildQuery → embed → buildFilter → retrieve, depends only on utterance + slots + scope.</li>
 * </ul>
 * Scope resolution and the scope-merged filter strategy run on the candidates
 * leg so the WARN-on-miss log appears whether or not the profile leg ever
 * resolves. After both legs finish, rerank → take Top K runs serially — identical
 * to the orchestrator. Reason generation is deferred to the merged EmotionAgent prompt.
 */
@Service
public class ParallelRecommendService {

    private static final Logger log = LoggerFactory.getLogger(ParallelRecommendService.class);
    private static final int FINAL_TOP_K = 3;

    private final UserProfileService profileService;
    private final EmbeddingService embeddingService;
    private final RecommendCandidateRetriever retriever;
    private final ProfileReranker reranker;
    private final SessionScopeCache scopeCache;
    private final ScopeFilterBuilder scopeFilterBuilder;
    private final SqlFilterBuilder sqlFilterBuilder;

    public ParallelRecommendService(UserProfileService profileService,
                                    EmbeddingService embeddingService,
                                    RecommendCandidateRetriever retriever,
                                    ProfileReranker reranker,
                                    SessionScopeCache scopeCache,
                                    ScopeFilterBuilder scopeFilterBuilder,
                                    SqlFilterBuilder sqlFilterBuilder) {
        this.profileService = profileService;
        this.embeddingService = embeddingService;
        this.retriever = retriever;
        this.reranker = reranker;
        this.scopeCache = scopeCache;
        this.scopeFilterBuilder = scopeFilterBuilder;
        this.sqlFilterBuilder = sqlFilterBuilder;
    }

    /**
     * Execute the recommendation pipeline with profile load and candidate
     * retrieval running in parallel.
     */
    public RecommendResult recommend(String sessionId,
                                     Long userId,
                                     String utterance,
                                     Map<String, Object> slots) {
        log.info("Parallel recommendation request: sessionId={}, userId={}, utterance={}, slots={}",
                sessionId, userId, utterance, slots);

        CompletableFuture<UserProfileSnapshot> profileF = CompletableFuture.supplyAsync(() ->
                userId != null ? profileService.load(userId) : null);

        CompletableFuture<List<RecommendedItem>> candidatesF = CompletableFuture.supplyAsync(() -> {
            String query = retriever.buildQuery(utterance, slots);
            log.debug("Embedding query: {}", query);
            // 在调用方包裹埋点，避免改 EmbeddingService 签名影响其他调用者；
            // SDK 不暴露 embedding 的 token usage，inputTokens 缺省。
            long embedT0 = System.currentTimeMillis();
            float[] queryVector = embeddingService.embed(query);
            com.voiceshopping.common.cost.CostMetricsLogger.logEmbedding(
                    "text-embedding-v3", query.length(), null,
                    System.currentTimeMillis() - embedT0);

            SessionScope scope = resolveScope(sessionId, userId);
            Filter scopeFilter = scopeFilterBuilder.build(scope);
            Function<Map<String, Object>, Filter> filterFn =
                    s -> sqlFilterBuilder.merge(retriever.buildFilter(s), scopeFilter);

            return retriever.retrieve(queryVector, slots, filterFn);
        });

        try {
            return profileF.thenCombine(candidatesF, (profile, candidates) -> {
                if (candidates.isEmpty()) {
                    log.info("No candidates found after all fallbacks");
                    return RecommendResult.EMPTY;
                }

                List<RecommendedItem> reranked = reranker.rerank(candidates, profile, slots);
                List<RecommendedItem> topK = reranked.stream()
                        .limit(FINAL_TOP_K)
                        .collect(Collectors.toList());

                // Reasons are now generated by the merged emotion prompt
                return new RecommendResult(topK, "professional");
            }).join();
        } catch (CompletionException e) {
            // Unwrap the original cause to preserve fail-fast semantics.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Parallel recommend failed: " + cause.getMessage(), cause);
        }
    }

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
