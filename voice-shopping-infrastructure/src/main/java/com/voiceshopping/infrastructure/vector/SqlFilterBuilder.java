package com.voiceshopping.infrastructure.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.dto.agent.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds SQL WHERE clause fragments from recommendation slots.
 * <p>
 * Provides generic slot-to-filter conversion, running-shoe-specific filtering,
 * and a merge utility for combining multiple filters.
 */
@Component
public class SqlFilterBuilder {

    private static final Logger log = LoggerFactory.getLogger(SqlFilterBuilder.class);

    private final ObjectMapper objectMapper;

    public SqlFilterBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Build filter from generic slot key-value pairs.
     * Recognized keys: budget, brand, category, categoryL2.
     * Unknown keys are mapped to JSONB attribute containment filters.
     */
    public Filter fromSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return Filter.EMPTY;
        }

        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        Object budget = slots.get("budget");
        if (budget instanceof Number b) {
            clauses.add("price <= ?");
            params.add(b.doubleValue());
        }

        Object priceMin = slots.get("priceMin");
        if (priceMin instanceof Number n) {
            clauses.add("price >= ?");
            params.add(n.doubleValue());
        }

        Object brand = slots.get("brand");
        if (brand instanceof String b && !b.isBlank()) {
            clauses.add("attributes @> CAST(? AS jsonb)");
            params.add(toJson(Map.of("brand", b)));
        }

        Object category = slots.get("category");
        if (category instanceof String c && !c.isBlank()) {
            clauses.add("category_l1 = ?");
            params.add(c);
        }

        Object categoryL2 = slots.get("categoryL2");
        if (categoryL2 instanceof String c2 && !c2.isBlank()) {
            clauses.add("category_l2 = ?");
            params.add(c2);
        }

        Object gender = slots.get("gender");
        if (gender != null) {
            clauses.add("attributes->>'gender' IN (?, 'unisex')");
            params.add(gender.toString());
        }

        // 排除上一轮已推过的商品，避免"换一款/便宜点"的时候又把同一批推回来
        Object exclude = slots.get("excludeProductIds");
        if (exclude instanceof Collection<?> c && !c.isEmpty()) {
            // Coerce Number → long: JSON deserialization may yield Integer for ids
            // that fit in 32 bits, but product.id is a BIGINT column.
            List<Long> ids = new ArrayList<>(c.size());
            for (Object o : c) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
            if (!ids.isEmpty()) {
                String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
                clauses.add("id NOT IN (" + placeholders + ")");
                params.addAll(ids);
            }
        }

        // Remaining unknown keys → JSONB containment
        // 有待商榷: whether unknown slot keys should auto-map to JSONB containment.
        // Risk: arbitrary keys may not match attribute structure, over-filtering results.
        // Disabled pending decision on explicit slot-to-attribute mapping.
        // for (Map.Entry<String, Object> entry : slots.entrySet()) {
        //     String key = entry.getKey();
        //     if (List.of("budget", "brand", "category", "categoryL2","excludeProductIds").contains(key)) {
        //         continue;
        //     }
        //     if (entry.getValue() != null) {
        //         clauses.add("attributes @> CAST(? AS jsonb)");
        //         params.add(toJson(Map.of(key, entry.getValue())));
        //     }
        // }

        if (clauses.isEmpty()) {
            return Filter.EMPTY;
        }
        return new Filter(String.join(" AND ", clauses), params);
    }

    /**
     * Running-shoe-specific filter: terrain type, cushion level, etc.
     * All conditions target the JSONB attributes column.
     */
    public Filter runningShoeFilter(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return Filter.EMPTY;
        }

        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        Object terrain = slots.get("terrain");
        if (terrain instanceof String t && !t.isBlank()) {
            clauses.add("attributes @> CAST(? AS jsonb)");
            params.add(toJson(Map.of("terrain", t)));
        }

        Object cushion = slots.get("cushion");
        if (cushion instanceof String c && !c.isBlank()) {
            clauses.add("attributes @> CAST(? AS jsonb)");
            params.add(toJson(Map.of("cushion", c)));
        }

        Object surface = slots.get("surface");
        if (surface instanceof String s && !s.isBlank()) {
            clauses.add("attributes @> CAST(? AS jsonb)");
            params.add(toJson(Map.of("surface", s)));
        }

        // Scenario-aware filter: pick cushion/terrain combo by running scenario.
        // scenario values: 水泥路 / 塑胶跑道 / 越野 / 其他(or unknown)
        Object scenario = slots.get("scenario");
        if (scenario instanceof String sc && !sc.isBlank()) {
            String scenarioClause = switch (sc.trim()) {
                // 水泥路: high/medium cushion, road-terrain tolerant
                case "水泥路" ->
                        "(attributes->>'cushion' IN ('high','medium') " +
                        "AND (attributes->>'terrain' IS NULL OR attributes->>'terrain' IN ('road')))";
                // 塑胶跑道: medium/low cushion, road/track-terrain tolerant
                case "塑胶跑道" ->
                        "(attributes->>'cushion' IN ('medium','low') " +
                        "AND (attributes->>'terrain' IS NULL OR attributes->>'terrain' IN ('road','track')))";
                // 越野: high cushion only, trail terrain required
                case "越野" ->
                        "(attributes->>'cushion' IN ('high') AND attributes->>'terrain' = 'trail')";
                // 其他 / unknown: no extra constraint
                default -> null;
            };
            if (scenarioClause != null) {
                clauses.add(scenarioClause);
            }
        }

        if (clauses.isEmpty()) {
            return Filter.EMPTY;
        }
        return new Filter(String.join(" AND ", clauses), params);
    }

    /**
     * Merge two filters: combine clauses with AND, concatenate params.
     * Either filter being empty returns the other.
     */
    public Filter merge(Filter a, Filter b) {
        if (a == null || a.isEmpty()) return b != null ? b : Filter.EMPTY;
        if (b == null || b.isEmpty()) return a;
        return new Filter(
                a.clause() + " AND " + b.clause(),
                concat(a.params(), b.params())
        );
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize filter value", e);
        }
    }

    private static List<Object> concat(List<Object> a, List<Object> b) {
        List<Object> result = new ArrayList<>(a.size() + b.size());
        result.addAll(a);
        result.addAll(b);
        return result;
    }
}
