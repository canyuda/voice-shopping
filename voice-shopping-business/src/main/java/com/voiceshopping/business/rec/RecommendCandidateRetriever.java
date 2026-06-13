package com.voiceshopping.business.rec;

import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.infrastructure.vector.SqlFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Candidate retrieval helper that owns the embedding-query construction,
 * SQL filter build (with running-shoe specialization), and progressive
 * fallback semantics (budget relax → drop categoryL2).
 * <p>
 * Extracted from {@link RecommendOrchestrator} so that
 * {@link ParallelRecommendService} can reuse the exact same fallback semantics
 * without duplicating the logic. Behavior is byte-for-byte equivalent to the
 * original private methods on RecommendOrchestrator.
 */
@Component
public class RecommendCandidateRetriever {

    private static final Logger log = LoggerFactory.getLogger(RecommendCandidateRetriever.class);
    private static final int INITIAL_TOP_N = 20;
    private static final double BUDGET_RELAX_RATIO = 1.30;

    private final SqlFilterBuilder sqlFilterBuilder;
    private final RecommendCandidatesService candidatesService;

    public RecommendCandidateRetriever(SqlFilterBuilder sqlFilterBuilder,
                                       RecommendCandidatesService candidatesService) {
        this.sqlFilterBuilder = sqlFilterBuilder;
        this.candidatesService = candidatesService;
    }

    /**
     * Build embedding query from slots keywords only (not utterance).
     * Falls back to utterance when slots are empty.
     */
    public String buildQuery(String utterance, Map<String, Object> slots) {
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
    public Filter buildFilter(Map<String, Object> slots) {
        Filter generic = sqlFilterBuilder.fromSlots(slots);

        // Apply running-shoe filter when category indicates running shoes
        Object category = slots.get("category");
        if (category instanceof String c && isRunningShoe(c)) {
            Filter shoeFilter = sqlFilterBuilder.runningShoeFilter(slots);
            return sqlFilterBuilder.merge(generic, shoeFilter);
        }

        return generic;
    }

    /**
     * Progressive fallback: relax filter when initial retrieval returns empty.
     * Reuses queryVector — only changes SQL filter params.
     * <p>
     * Order:
     * <ol>
     *   <li>Original filter</li>
     *   <li>Budget +30%</li>
     *   <li>Drop categoryL2</li>
     * </ol>
     */
    public List<RecommendedItem> retrieve(float[] queryVector,
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

    private boolean isRunningShoe(String category) {
        return category.contains("跑鞋") || category.equalsIgnoreCase("running_shoes");
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
