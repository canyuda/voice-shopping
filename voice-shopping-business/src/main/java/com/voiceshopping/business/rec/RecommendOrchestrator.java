package com.voiceshopping.business.rec;

import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the full recommendation pipeline:
 * load profile → build query → embed → filter → retrieve candidates →
 * fallback → rerank → top 3 → generate reasons → return result.
 * <p>
 * Query construction, filter build, and progressive fallback are delegated to
 * {@link RecommendCandidateRetriever} so that {@link ParallelRecommendService}
 * can reuse the same logic without duplication. The external behavior of
 * {@link #recommend} (signature, return value, empty-result and exception
 * semantics) is preserved exactly.
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

    public RecommendOrchestrator(UserProfileService profileService,
                                  EmbeddingService embeddingService,
                                  RecommendCandidateRetriever retriever,
                                  ProfileReranker reranker,
                                  RecommendReasonService reasonService) {
        this.profileService = profileService;
        this.embeddingService = embeddingService;
        this.retriever = retriever;
        this.reranker = reranker;
        this.reasonService = reasonService;
    }

    /**
     * Execute the full recommendation pipeline.
     *
     * @param sessionId session ID for agent cache
     * @param userId    user ID for profile loading
     * @param utterance user's original speech text
     * @param slots     extracted slot values (category, budget, brand, etc.)
     * @return recommendation result with top-K items and reasons
     */
    public RecommendResult recommend(String sessionId,
                                      Long userId,
                                      String utterance,
                                      Map<String, Object> slots) {
        log.info("Recommendation request: userId={}, utterance={}, slots={}", userId, utterance, slots);

        // 1. Load user profile
        UserProfileSnapshot profile = userId != null ? profileService.load(userId) : null;

        // 2. Build query from slots keywords only
        String query = retriever.buildQuery(utterance, slots);
        log.debug("Embedding query: {}", query);

        // 3. Generate query vector (reusable across fallback retries)
        float[] queryVector = embeddingService.embed(query);

        // 4. Build SQL filter (with running-shoe specialization)
        Filter filter = retriever.buildFilter(slots);

        // 5. Retrieve candidates with fallback
        List<RecommendedItem> candidates = retriever.retrieve(queryVector, filter, slots);

        // 6. Empty result after all fallbacks
        if (candidates.isEmpty()) {
            log.info("No candidates found after all fallbacks");
            return RecommendResult.EMPTY;
        }

        // 7. Rerank by profile signals
        List<RecommendedItem> reranked = reranker.rerank(candidates, profile, slots);

        // 8. Take top K
        List<RecommendedItem> topK = reranked.stream()
                .limit(FINAL_TOP_K)
                .collect(Collectors.toList());

        // 9. Generate reasons via LLM
        String userNeeds = utterance + "; 槽位：" + slots;
        List<RecommendedItem> withReasons = reasonService.attachReasons(sessionId, userNeeds, topK);

        // 10. Return result
        return new RecommendResult(withReasons, "professional");
    }
}
