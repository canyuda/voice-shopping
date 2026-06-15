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
import java.util.function.Function;

/**
 * Candidate retrieval helper that owns the embedding-query construction,
 * SQL filter build (with running-shoe specialization), and progressive
 * fallback semantics (budget relax → drop categoryL2).
 * <p>
 * Extracted from {@link RecommendOrchestrator} so that
 * {@link ParallelRecommendService} can reuse the exact same fallback semantics
 * without duplicating the logic.
 * <p>
 * <b>Scope contract:</b> the retriever itself is scope-agnostic. The orchestrator
 * supplies a {@code Function<Map<String,Object>, Filter>} strategy to
 * {@link #retrieve(float[], Map, Function)} so each fallback retry rebuilds the
 * filter through the same lambda — guaranteeing scope (e.g. {@code merchant_id IN (...)})
 * is re-merged on every relaxed retry.
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
     * Build combined slot filter: generic fromSlots + running-shoe specialization.
     * <p>
     * Returns a generic (scope-unaware) filter — orchestrators are expected to
     * compose this with a {@link com.voiceshopping.infrastructure.vector.ScopeFilterBuilder}
     * output via {@link SqlFilterBuilder#merge(Filter, Filter)}.
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
     * The {@code filterFn} is invoked once per attempt (initial + each fallback
     * with modified slots) so any cross-cutting fragment composed by the caller
     * (e.g. scope merge) is preserved across retries.
     * <p>
     * Order:
     * <ol>
     *   <li>Original filter</li>
     *   <li>Budget +30%</li>
     *   <li>Drop categoryL2</li>
     * </ol>
     */
    public List<RecommendedItem> retrieve(float[] queryVector,
                                          Map<String, Object> slots,
                                          Function<Map<String, Object>, Filter> filterFn) {
        Filter filter = filterFn.apply(slots);

        // Attempt 1: original filter
        List<RecommendedItem> candidates = candidatesService.fetchCandidates(queryVector, filter, INITIAL_TOP_N);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        log.debug("Initial retrieval empty, trying budget relax");

        // Attempt 2: relax budget by +30%
        Filter relaxedBudget = filterFn.apply(relaxBudgetSlots(slots));
        if (!relaxedBudget.equals(filter)) {
            candidates = candidatesService.fetchCandidates(queryVector, relaxedBudget, INITIAL_TOP_N);
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }
        log.debug("Budget-relaxed retrieval empty, trying without category_l2");

        // Attempt 3: drop category_l2 filter
        Filter noCategoryL2 = filterFn.apply(dropCategoryL2Slots(slots));
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

    /** Slots variant with budget bumped 30% — does not rebuild a filter. */
    private Map<String, Object> relaxBudgetSlots(Map<String, Object> slots) {
        Map<String, Object> relaxed = new LinkedHashMap<>(slots);
        Object budget = relaxed.get("budget");
        if (budget instanceof Number n) {
            relaxed.put("budget", n.doubleValue() * BUDGET_RELAX_RATIO);
        }
        return relaxed;
    }

    /** Slots variant with categoryL2 stripped — does not rebuild a filter. */
    private Map<String, Object> dropCategoryL2Slots(Map<String, Object> slots) {
        Map<String, Object> modified = new LinkedHashMap<>(slots);
        modified.remove("categoryL2");
        return modified;
    }
}
