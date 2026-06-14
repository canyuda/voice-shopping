package com.voiceshopping.business.compliance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compliance configuration bound from {@code voice-shopping.compliance.*}.
 * <p>
 * {@code @ConfigurationProperties} is the only reliable way to inject a nested
 * YAML map into a {@code Map<String, String>} — {@code @Value} expands each
 * key-value pair into a separate property and trips SpEL parsing.
 * <p>
 * The map preserves insertion order (LinkedHashMap), which matters because the
 * checker applies replacements left-to-right and longer keys ("最便宜") MUST
 * fire before substrings ("便宜") to avoid partial-overlap surprises.
 */
@Component
@ConfigurationProperties(prefix = "voice-shopping.compliance")
public class ComplianceProperties {

    /** Absolute-claim → replacement. Empty by default. */
    private Map<String, String> absoluteClaimPatterns = new LinkedHashMap<>();

    public Map<String, String> getAbsoluteClaimPatterns() {
        return absoluteClaimPatterns;
    }

    public void setAbsoluteClaimPatterns(Map<String, String> absoluteClaimPatterns) {
        this.absoluteClaimPatterns = absoluteClaimPatterns;
    }
}
