package com.voiceshopping.business.agent;

import com.voiceshopping.business.memory.ShortTermMemory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight rule-based session mood detector.
 * <p>
 * Runs entirely on regex + short-term memory — no LLM call — to keep the
 * emotion pipeline's hot path latency-free. Priority chain:
 * <ol>
 *   <li>{@link #byRule(String)}: impatient &gt; negative &gt; positive.</li>
 *   <li>hesitation: among the recent turns, ≥2 assistant messages end with
 *       a question mark.</li>
 *   <li>neutral fallback.</li>
 * </ol>
 */
@Component
public class SessionMoodDetector {

    public static final String IMPATIENT = "impatient";
    public static final String NEGATIVE = "negative";
    public static final String POSITIVE = "positive";
    public static final String HESITANT = "hesitant";
    public static final String NEUTRAL = "neutral";

    private static final Pattern IMPATIENT_RE = Pattern.compile("算了|不要|别说了|快点|到底|跳过");
    private static final Pattern NEGATIVE_RE = Pattern.compile("贵|太贵|不喜欢|丑|不行|不对");
    private static final Pattern POSITIVE_RE = Pattern.compile("好的|可以|不错|挺好|行|ok", Pattern.CASE_INSENSITIVE);

    private static final int RECENT_TURNS_FOR_HESITATION = 3;
    private static final int HESITATION_QUESTION_THRESHOLD = 2;

    private final ShortTermMemory shortTermMemory;

    public SessionMoodDetector(ShortTermMemory shortTermMemory) {
        this.shortTermMemory = shortTermMemory;
    }

    /**
     * Detect the current session mood for the given utterance.
     *
     * @param sessionId        current session id
     * @param currentUtterance user's latest utterance (may be null)
     * @return one of impatient/negative/positive/hesitant/neutral
     */
    public String detect(String sessionId, String currentUtterance) {
        String ruled = byRule(currentUtterance);
        if (ruled != null) {
            return ruled;
        }
        if (isHesitant(sessionId)) {
            return HESITANT;
        }
        return NEUTRAL;
    }

    /**
     * Regex-based mood classification. Priority: impatient &gt; negative &gt; positive.
     *
     * @param text utterance text, null-safe
     * @return mood label, or null if no rule matches (or input is null)
     */
    String byRule(String text) {
        if (text == null) {
            return null;
        }
        if (IMPATIENT_RE.matcher(text).find()) {
            return IMPATIENT;
        }
        if (NEGATIVE_RE.matcher(text).find()) {
            return NEGATIVE;
        }
        if (POSITIVE_RE.matcher(text).find()) {
            return POSITIVE;
        }
        return null;
    }

    /**
     * Hesitation signal: among the most recent turns, if ≥2 assistant turns
     * end with a question mark, the user is likely being asked repeatedly.
     */
    private boolean isHesitant(String sessionId) {
        List<ShortTermMemory.Turn> recent = shortTermMemory.recent(sessionId, RECENT_TURNS_FOR_HESITATION);
        long questionEndings = recent.stream()
                .filter(t -> "ASSISTANT".equals(t.role()))
                .map(ShortTermMemory.Turn::content)
                .filter(SessionMoodDetector::endsWithQuestion)
                .count();
        return questionEndings >= HESITATION_QUESTION_THRESHOLD;
    }

    private static boolean endsWithQuestion(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.endsWith("?") || trimmed.endsWith("？");
    }
}
