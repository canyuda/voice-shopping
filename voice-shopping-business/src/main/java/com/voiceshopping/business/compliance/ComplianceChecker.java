package com.voiceshopping.business.compliance;

import com.voiceshopping.common.dto.agent.EmotionResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Final-stage compliance pass over the spoken reply.
 * <p>
 * Runs two scans in order:
 * <ol>
 *   <li><b>Absolute-claim rewrite</b> — replace marketing absolutes (e.g. "最便宜"
 *       → "性价比高") per {@code voice-shopping.compliance.absolute-claim-patterns}.</li>
 *   <li><b>Sensitive-word masking</b> — replace each occurrence of a sensitive
 *       word loaded from {@code compliance/sensitive-words.txt} with {@code *}
 *       repeated to match the original word's character length.</li>
 * </ol>
 * Emits a WARN log when any rewrite happens. Returns a fresh {@link EmotionResult}
 * — never mutates the input. {@code displayBlocks} is passed through unchanged
 * (compliance scoping is utterance-only for this pass).
 */
@Component
public class ComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(ComplianceChecker.class);

    private static final String SENSITIVE_WORDS_PATH = "compliance/sensitive-words.txt";

    /** Absolute-claim → replacement, configured via application.yml. */
    private final Map<String, String> absoluteClaimPatterns;

    /** Loaded once at startup; never mutated thereafter. */
    private Set<String> sensitiveWords = Set.of();

    public ComplianceChecker(ComplianceProperties properties) {
        // Preserve insertion order so longer keys (e.g. "最便宜") can be ordered
        // before substrings (e.g. "便宜") in the YAML, defeating partial-overlap surprises.
        Map<String, String> raw = properties.getAbsoluteClaimPatterns();
        this.absoluteClaimPatterns = (raw == null || raw.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    @PostConstruct
    void init() {
        Set<String> loaded = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(SENSITIVE_WORDS_PATH).getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty()) {
                    loaded.add(word);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + SENSITIVE_WORDS_PATH, e);
        }
        this.sensitiveWords = Collections.unmodifiableSet(loaded);
        log.info("ComplianceChecker initialized: sensitiveWords={}, absoluteClaimPatterns={}",
                sensitiveWords.size(), absoluteClaimPatterns.size());
    }

    /**
     * Apply compliance rewrites on the reply's speech text.
     *
     * @param sessionId active session id (for logs)
     * @param userId    user id (for logs)
     * @param reply     original reply
     * @return new EmotionResult with possibly rewritten speech; same displayBlocks
     */
    public EmotionResult ensureCompliant(String sessionId, Long userId, EmotionResult reply) {
        if (reply == null || reply.speechText() == null || reply.speechText().isEmpty()) {
            return reply;
        }

        String original = reply.speechText();
        String afterAbsolutes = rewriteAbsoluteClaims(original);
        String afterSensitive = maskSensitiveWords(afterAbsolutes);

        if (!original.equals(afterSensitive)) {
            log.warn("Compliance rewrite applied: sessionId={}, userId={}, before=\"{}\", after=\"{}\"",
                    sessionId, userId, original, afterSensitive);
            return new EmotionResult(afterSensitive, reply.displayBlocks());
        }
        return reply;
    }

    private String rewriteAbsoluteClaims(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : absoluteClaimPatterns.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (result.contains(key)) {
                result = result.replace(key, entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return result;
    }

    private String maskSensitiveWords(String text) {
        String result = text;
        for (String word : sensitiveWords) {
            if (word.isEmpty() || !result.contains(word)) {
                continue;
            }
            result = result.replace(word, "*".repeat(word.length()));
        }
        return result;
    }
}
