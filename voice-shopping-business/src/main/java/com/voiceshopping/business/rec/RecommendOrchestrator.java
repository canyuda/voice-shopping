package com.voiceshopping.business.rec;

import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import com.voiceshopping.infrastructure.vector.SqlFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the full recommendation pipeline:
 * load profile → build query → embed → filter → retrieve candidates →
 * fallback → rerank → top 3 → generate reasons → return result.
 */
@Service
public class RecommendOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecommendOrchestrator.class);
    private static final int INITIAL_TOP_N = 20;
    private static final int FINAL_TOP_K = 3;
    private static final double BUDGET_RELAX_RATIO = 1.30;

    private final UserProfileService profileService;
    private final EmbeddingService embeddingService;
    private final SqlFilterBuilder sqlFilterBuilder;
    private final RecommendCandidatesService candidatesService;
    private final ProfileReranker reranker;
    private final RecommendReasonService reasonService;

    public RecommendOrchestrator(UserProfileService profileService,
                                  EmbeddingService embeddingService,
                                  SqlFilterBuilder sqlFilterBuilder,
                                  RecommendCandidatesService candidatesService,
                                  ProfileReranker reranker,
                                  RecommendReasonService reasonService) {
        this.profileService = profileService;
        this.embeddingService = embeddingService;
        this.sqlFilterBuilder = sqlFilterBuilder;
        this.candidatesService = candidatesService;
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
        String query = buildQuery(utterance, slots);
        log.debug("Embedding query: {}", query);

        // 3. Generate query vector (reusable across fallback retries)
        float[] queryVector = embeddingService.embed(query);

        // 4. Build SQL filter (with running-shoe specialization)
        Filter filter = buildFilter(slots);

        // 5. Retrieve candidates with fallback
        List<RecommendedItem> candidates = retrieveWithFallback(queryVector, filter, slots);

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

    /**
     * Build embedding query from slots keywords only (not utterance).
     * Falls back to utterance when slots are empty.
     */
    String buildQuery(String utterance, Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return utterance;
        }

        List<String> keywords = new ArrayList<>();
        for (Map.Entry<String, Object> entry : slots.entrySet()) {
            if (entry.getValue() != null) {
                keywords.add(String.valueOf(entry.getValue()));
            }
        }

        if (keywords.isEmpty()) {
            return utterance;
        }
        return String.join(" ", keywords);
    }

    /**
     * Build combined filter: generic fromSlots + running-shoe specialization.
     */
    private Filter buildFilter(Map<String, Object> slots) {
        Filter generic = sqlFilterBuilder.fromSlots(slots);

        // Apply running-shoe filter when category indicates running shoes
        Object category = slots.get("category");
        if (category instanceof String c && isRunningShoe(c)) {
            Filter shoeFilter = sqlFilterBuilder.runningShoeFilter(slots);
            return sqlFilterBuilder.merge(generic, shoeFilter);
        }

        return generic;
    }

    private boolean isRunningShoe(String category) {
        return category.contains("跑鞋") || category.equalsIgnoreCase("running_shoes");
    }

    /**
     * Progressive fallback: relax filter when initial retrieval returns empty.
     * Reuses queryVector — only changes SQL filter params.
     */
    private List<RecommendedItem> retrieveWithFallback(float[] queryVector,
                                                        Filter filter,
                                                        Map<String, Object> slots) {
        // Attempt 1: original filter
        List<RecommendedItem> candidates = candidatesService.fetchCandidates(queryVector, filter, INITIAL_TOP_N);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        log.debug("Initial retrieval empty, trying budget relax");

        // Attempt 2: relax budget by +30%
        Filter relaxedBudget = relaxBudget(slots);
        if (!relaxedBudget.equals(filter)) {
            candidates = candidatesService.fetchCandidates(queryVector, relaxedBudget, INITIAL_TOP_N);
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }
        log.debug("Budget-relaxed retrieval empty, trying without category_l2");

        // Attempt 3: drop category_l2 filter
        Filter noCategoryL2 = dropCategoryL2(slots);
        if (!noCategoryL2.equals(filter)) {
            candidates = candidatesService.fetchCandidates(queryVector, noCategoryL2, INITIAL_TOP_N);
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }
        log.debug("All fallbacks exhausted, returning empty");

        return List.of();
    }

    /**
     * Relax budget by increasing it 30% in slots, rebuild filter.
     */
    private Filter relaxBudget(Map<String, Object> slots) {
        Map<String, Object> relaxed = new LinkedHashMap<>(slots);
        Object budget = relaxed.get("budget");
        if (budget instanceof Number n) {
            relaxed.put("budget", n.doubleValue() * BUDGET_RELAX_RATIO);
        }
        return buildFilter(relaxed);
    }

    /**
     * Remove categoryL2 from slots, rebuild filter.
     */
    private Filter dropCategoryL2(Map<String, Object> slots) {
        Map<String, Object> modified = new LinkedHashMap<>(slots);
        modified.remove("categoryL2");
        return buildFilter(modified);
    }
}
