package com.voiceshopping.business.agent;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads per-category slot requirement rules from {@code clarify/required-slots.yml}
 * and computes which slots are missing for a given category.
 */
@Component
public class ClarifyRuleService {

    private static final Logger log = LoggerFactory.getLogger(ClarifyRuleService.class);
    private static final String CONFIG_PATH = "clarify/required-slots.yml";

    private Map<String, CategoryRule> rules = Map.of();

    @PostConstruct
    void loadRules() {
        try (InputStream in = new ClassPathResource(CONFIG_PATH).getInputStream()) {
            Map<String, Map<String, Object>> raw = new Yaml().load(in);
            Map<String, CategoryRule> parsed = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                parsed.put(entry.getKey(), parseRule(entry.getValue()));
            }
            this.rules = Collections.unmodifiableMap(parsed);
            log.info("Loaded {} category rules from {}", rules.size(), CONFIG_PATH);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + CONFIG_PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private CategoryRule parseRule(Map<String, Object> raw) {
        CategoryRule rule = new CategoryRule();
        rule.setRequired((List<String>) raw.getOrDefault("required", List.of()));
        rule.setNiceToHave((List<String>) raw.getOrDefault("nice_to_have", List.of()));
        rule.setScenarioOptions((List<String>) raw.getOrDefault("scenario_options", List.of()));
        return rule;
    }

    /**
     * Returns the required slot names that are missing from the given slot map.
     * Only checks required fields; nice-to-have fields are not gating.
     *
     * @param category the product category (e.g. "跑鞋"), falls back to default if unknown
     * @param slots    currently extracted slot values
     * @return ordered list of missing required slot names, empty if all required are filled
     */
    public List<String> missingSlots(String category, Map<String, Object> slots) {
        CategoryRule rule = rules.getOrDefault(category, rules.get("default"));
        if (rule == null) {
            return List.of();
        }

        List<String> missing = new ArrayList<>();
        for (String field : rule.getRequired()) {
            if (isMissing(slots, field)) {
                missing.add(field);
            }
        }
        return Collections.unmodifiableList(missing);
    }

    private boolean isMissing(Map<String, Object> slots, String field) {
        Object value = slots.get(field);
        return value == null || (value instanceof String s && s.isBlank());
    }

    // ---- package-private for testing ----

    int ruleCount() {
        return rules.size();
    }

    // ---- inner types ----

    @Data
    public static class CategoryRule {
        private List<String> required;
        private List<String> niceToHave;
        private List<String> scenarioOptions;
    }
}
