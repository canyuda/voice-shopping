package com.voiceshopping.business.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.business.agent.ClarifyRuleService;
import com.voiceshopping.business.agent.ClarifyService;
import com.voiceshopping.business.agent.EmotionService;
import com.voiceshopping.business.agent.IntentService;
import com.voiceshopping.business.compliance.ComplianceChecker;
import com.voiceshopping.business.event.VoiceEventPublisher;
import com.voiceshopping.business.memory.ShortTermMemory;
import com.voiceshopping.business.perspective.PerspectiveHubService;
import com.voiceshopping.business.rec.ParallelRecommendService;
import com.voiceshopping.business.session.SessionStateService;
import com.voiceshopping.common.dto.agent.ClarifyResult;
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.common.dto.agent.IntentResult;
import com.voiceshopping.common.dto.agent.LastRecommendationsSnapshot;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.enums.IntentEnum;
import com.voiceshopping.common.event.UserSpokenEvent;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.SessionRepository;
import com.voiceshopping.infrastructure.repository.entity.Session;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation orchestrator — single entry point that wires together
 * intent understanding, clarification, recommendation, perspective discussion
 * (optional), emotion wrapping, compliance fallback, and session-state writeback.
 * <p>
 * Exposes exactly one public method: {@link #handle(String, Long, String)}.
 * Web layer (WebSocket / Controllers) is responsible for ensuring the session
 * exists before calling — handle does NOT create sessions.
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    /** Allowed values for {@code session_state.phase}. */
    static final class SessionPhase {
        static final String INTENT = "INTENT";
        static final String CLARIFY = "CLARIFY";
        static final String RECOMMEND = "RECOMMEND";
        static final String ORDER_CONFIRM = "ORDER_CONFIRM";
        static final String ENDED = "ENDED";

        private SessionPhase() {}
    }

    /** Recommendation-style empty fallback that EmotionService produces — replaced in CHITCHAT. */
    private static final String EMOTION_EMPTY_FALLBACK =
            "这个条件下合适的不多，要不要放宽点预算再看看？";
    private static final String CHITCHAT_FALLBACK =
            "不太懂这个，我们聊点你想买啥呗？";
    private static final String ORDER_CONFIRM_REPLY =
            "好，给你下单（完整下单逻辑后续补）";
    private static final String OUT_OF_SCOPE_REPLY =
            "只负责帮你挑商品，这个问题回头可以找客服处理哈。我们继续聊想买什么？";

    // --- Dependencies (D1~D11) ---
    private final SessionRepository sessionRepository;
    private final SessionStateService sessionStateService;
    private final ShortTermMemory shortTermMemory;
    private final VoiceEventPublisher voiceEventPublisher;
    private final IntentService intentService;
    private final ClarifyService clarifyService;
    private final ClarifyRuleService clarifyRuleService;
    private final ParallelRecommendService parallelRecommendService;
    private final PerspectiveHubService perspectiveHubService;
    private final EmotionService emotionService;
    private final ComplianceChecker complianceChecker;
    private final ObjectMapper objectMapper;

    private final boolean perspectiveEnabled;

    /** Pre-built timers per intent — avoid Timer.builder allocation on every call. */
    private final Map<IntentEnum, Timer> timersByIntent;

    public OrchestratorService(SessionRepository sessionRepository,
                               SessionStateService sessionStateService,
                               ShortTermMemory shortTermMemory,
                               VoiceEventPublisher voiceEventPublisher,
                               IntentService intentService,
                               ClarifyService clarifyService,
                               ClarifyRuleService clarifyRuleService,
                               ParallelRecommendService parallelRecommendService,
                               PerspectiveHubService perspectiveHubService,
                               EmotionService emotionService,
                               ComplianceChecker complianceChecker,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry,
                               @Value("${voice-shopping.perspective.enabled:false}") boolean perspectiveEnabled) {
        this.sessionRepository = sessionRepository;
        this.sessionStateService = sessionStateService;
        this.shortTermMemory = shortTermMemory;
        this.voiceEventPublisher = voiceEventPublisher;
        this.intentService = intentService;
        this.clarifyService = clarifyService;
        this.clarifyRuleService = clarifyRuleService;
        this.parallelRecommendService = parallelRecommendService;
        this.perspectiveHubService = perspectiveHubService;
        this.emotionService = emotionService;
        this.complianceChecker = complianceChecker;
        this.objectMapper = objectMapper;
        this.perspectiveEnabled = perspectiveEnabled;

        // Pre-build one Timer per IntentEnum so finally-block lookup is O(1) (D13).
        this.timersByIntent = new EnumMap<>(IntentEnum.class);
        for (IntentEnum intent : IntentEnum.values()) {
            Timer timer = Timer.builder("voice.shopping.orchestrator.handle")
                    .tag("intent", intent.name())
                    .register(meterRegistry);
            this.timersByIntent.put(intent, timer);
        }
        log.info("OrchestratorService initialized: perspectiveEnabled={}", perspectiveEnabled);
    }

    /**
     * Handle one user utterance, returning the spoken reply + display blocks.
     * Caller MUST have created the session beforehand.
     *
     * @throws NotFoundException when {@code sessionId} does not match an existing session
     */
    public EmotionResult handle(String sessionId, Long userId, String utterance) {
        Timer.Sample sample = Timer.start();
        IntentEnum finalIntent = IntentEnum.OUT_OF_SCOPE;

        try {
            // 1. Find session — fail fast when not present (D1)
            Session session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new NotFoundException("会话不存在: " + sessionId));

            // 2. Side-channel events: profile warm-up etc.
            voiceEventPublisher.publish(new UserSpokenEvent(
                    sessionId, userId, utterance, System.currentTimeMillis()));

            // 3. Append USER turn — must be persisted before intent classification
            //    so subsequent agents see it via ShortTermMemory.recent().
            shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                    "USER", utterance, null, Instant.now()));

            // 4. Load session_state (or initialize a fresh one)
            SessionState state = sessionStateService.load(sessionId)
                    .orElseGet(() -> initialState(sessionId, session.getMerchantId()));

            // 5. Intent classification
            IntentResult intent = intentService.classify(sessionId, utterance);
            log.info("Intent classified for session={}: {} (confidence={})",
                    sessionId, intent.intent(), intent.confidence());

            // 6. Apply revision rules (priceDirection anchor / info-sufficient)
            RevisedIntent revised = reviseIntent(intent, state);
            finalIntent = revised.intent();
            log.info("Intent revised for session={}: {} → {}", sessionId, intent.intent(), finalIntent);

            // 7. Dispatch by final intent (each branch returns the BranchOutcome
            //    carrying EmotionResult + branch-specific writeback markers)
            BranchOutcome outcome = dispatch(sessionId, userId, utterance, state, revised);

            // 8. Compliance pass-through (placeholder)
            EmotionResult safeReply = complianceChecker.ensureCompliant(sessionId, userId, outcome.reply());

            // 9. Append ASSISTANT turn — agent tag carries the final (post-revision)
            //    intent name so downstream consumers can attribute replies.
            shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                    "ASSISTANT", safeReply.speechText(), finalIntent.name(), Instant.now()));

            // 10. Persist final session_state
            persistState(state, finalIntent, revised.mergedSlots(), outcome);

            return safeReply;
        } finally {
            sample.stop(timersByIntent.get(finalIntent));
        }
    }

    // ----------------------------------------------------------------------
    // Intent revision (D7)
    // ----------------------------------------------------------------------

    /**
     * Apply the two revision rules in order. Returns the final intent + merged slots.
     */
    RevisedIntent reviseIntent(IntentResult result, SessionState state) {
        Map<String, Object> merged = mergeSlots(state.getSlots(), result.slots());
        IntentEnum llmIntent = result.intent();

        // Rule ① — priceDirection anchoring: if the previous turn produced
        // recommendations and this turn carries a recognized direction, force COMPARE.
        if (state.getLastRecommendations() != null && !state.getLastRecommendations().isEmpty()) {
            Object dir = merged.get("priceDirection");
            if ("cheaper".equals(dir) || "expensive".equals(dir)) {
                if (llmIntent == IntentEnum.PRODUCT_RECOMMENDATION
                        || llmIntent == IntentEnum.CLARIFY_NEEDED) {
                    return new RevisedIntent(IntentEnum.PRODUCT_COMPARE, merged);
                }
            }
        }

        // Rule ② — info-sufficient: only escalates CLARIFY_NEEDED when slots are
        // already complete per ClarifyRuleService rules.
        if (llmIntent == IntentEnum.CLARIFY_NEEDED) {
            String category = (String) merged.get("category");
            if (clarifyRuleService.missingSlots(category, merged).isEmpty()) {
                return new RevisedIntent(IntentEnum.PRODUCT_RECOMMENDATION, merged);
            }
        }

        return new RevisedIntent(llmIntent, merged);
    }

    /**
     * Merge state slots with current-turn slots: current overrides when value is non-null.
     */
    Map<String, Object> mergeSlots(Map<String, Object> stateSlots, Map<String, Object> currentSlots) {
        Map<String, Object> merged = new HashMap<>();
        if (stateSlots != null) {
            merged.putAll(stateSlots);
        }
        if (currentSlots != null) {
            for (Map.Entry<String, Object> e : currentSlots.entrySet()) {
                if (e.getValue() != null) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        return merged;
    }

    // ----------------------------------------------------------------------
    // Dispatch — each branch is implemented in section 5
    // ----------------------------------------------------------------------

    private BranchOutcome dispatch(String sessionId, Long userId, String utterance,
                                   SessionState state, RevisedIntent revised) {
        return switch (revised.intent()) {
            case PRODUCT_RECOMMENDATION -> runRecommendation(sessionId, userId, utterance, revised.mergedSlots());
            case CLARIFY_NEEDED         -> runClarify(sessionId, userId, utterance, revised.mergedSlots());
            case PRODUCT_COMPARE        -> runCompare(sessionId, userId, utterance, state, revised.mergedSlots());
            case ORDER_CONFIRM          -> runOrderConfirm();
            case CHITCHAT               -> runChitchat(sessionId, utterance);
            case OUT_OF_SCOPE           -> runOutOfScope();
        };
    }

    // --- Branch implementations ---

    BranchOutcome runRecommendation(String sessionId, Long userId, String utterance,
                                    Map<String, Object> slots) {
        // 1. Clarify decision — ASK short-circuits.
        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        if (clarify.action() == ClarifyResult.Action.ASK) {
            return new BranchOutcome(
                    new EmotionResult(clarify.questionToAsk(), List.of()),
                    SessionPhase.CLARIFY,
                    clarify.questionToAsk(),
                    null);
        }

        // 2. Recommendation pipeline.
        RecommendResult rec = parallelRecommendService.recommend(sessionId, userId, utterance, slots);

        // 3. Perspective discussion (D12) — only when enabled and we actually have items.
        String finalUtterance = utterance;
        if (perspectiveEnabled && rec.items() != null && !rec.items().isEmpty()) {
            String panel = perspectiveHubService.discuss(sessionId, utterance, rec.items());
            if (panel != null && !panel.isBlank()) {
                finalUtterance = utterance + "\n\n[多视角点评]\n" + panel;
            }
        }

        // 4. Emotion wrap.
        EmotionResult reply = emotionService.wrap(sessionId, finalUtterance, rec);
        return new BranchOutcome(reply, SessionPhase.RECOMMEND, null, rec.items());
    }

    BranchOutcome runClarify(String sessionId, Long userId, String utterance,
                             Map<String, Object> slots) {
        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        if (clarify.action() == ClarifyResult.Action.ASK) {
            return new BranchOutcome(
                    new EmotionResult(clarify.questionToAsk(), List.of()),
                    SessionPhase.CLARIFY,
                    clarify.questionToAsk(),
                    null);
        }
        // ClarifyService says READY despite the LLM saying CLARIFY — slot rules
        // disagree with the model. Trust the rules and run the full pipeline.
        return runRecommendation(sessionId, userId, utterance, slots);
    }

    BranchOutcome runCompare(String sessionId, Long userId, String utterance,
                             SessionState state, Map<String, Object> slots) {
        LastRecommendationsSnapshot snap = readLastRecommendations(state);
        if (snap == null || snap.items().isEmpty()) {
            log.info("PRODUCT_COMPARE without prior recommendations — falling back to RECOMMENDATION");
            return runRecommendation(sessionId, userId, utterance, slots);
        }

        Map<String, Object> compareSlots = new HashMap<>(slots);
        Object dir = compareSlots.get("priceDirection");
        if ("cheaper".equals(dir) && snap.maxPrice() != null) {
            // cheaper: tighten budget below the previous max — guarantees strict improvement.
            BigDecimal newBudget = snap.maxPrice().multiply(new BigDecimal("0.8"));
            compareSlots.put("budget", newBudget);
        } else if ("expensive".equals(dir) && snap.minPrice() != null) {
            // expensive: set a lower bound, leaving the user-given budget as the upper bound (D3).
            BigDecimal newPriceMin = snap.minPrice().multiply(new BigDecimal("1.2"));
            compareSlots.put("priceMin", newPriceMin);
        }
        // Always exclude prior items so we don't echo the same SKU.
        if (!snap.productIds().isEmpty()) {
            compareSlots.put("excludeProductIds", snap.productIds());
        }

        RecommendResult rec = parallelRecommendService.recommend(sessionId, userId, utterance, compareSlots);
        EmotionResult reply = emotionService.wrap(sessionId, utterance, rec);
        return new BranchOutcome(reply, SessionPhase.RECOMMEND, null, rec.items());
    }

    BranchOutcome runOrderConfirm() {
        return new BranchOutcome(
                new EmotionResult(ORDER_CONFIRM_REPLY, List.of()),
                SessionPhase.ORDER_CONFIRM,
                null,
                null);
    }

    BranchOutcome runChitchat(String sessionId, String utterance) {
        EmotionResult raw = emotionService.wrap(sessionId, utterance, RecommendResult.EMPTY);
        // EmotionService produces a recommendation-style fallback when items are empty;
        // swap it for a chitchat-style reply (D9). Drop this once the EmotionAgent
        // prompt understands chitchat mode natively.
        EmotionResult reply = EMOTION_EMPTY_FALLBACK.equals(raw.speechText())
                ? new EmotionResult(CHITCHAT_FALLBACK, List.of())
                : raw;
        return new BranchOutcome(reply, SessionPhase.INTENT, null, null);
    }

    BranchOutcome runOutOfScope() {
        return new BranchOutcome(
                new EmotionResult(OUT_OF_SCOPE_REPLY, List.of()),
                SessionPhase.INTENT,
                null,
                null);
    }

    /**
     * Read the prior turn's recommendations from session_state JSONB.
     * Returns null on missing or unreadable payload — callers degrade gracefully.
     */
    private LastRecommendationsSnapshot readLastRecommendations(SessionState state) {
        Map<String, Object> raw = state.getLastRecommendations();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(raw, LastRecommendationsSnapshot.class);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse last_recommendations for session={}, treating as missing: {}",
                    state.getId(), e.getMessage());
            return null;
        }
    }

    // ----------------------------------------------------------------------
    // State writeback (filled in section 6)
    // ----------------------------------------------------------------------

    void persistState(SessionState state, IntentEnum finalIntent,
                      Map<String, Object> mergedSlots, BranchOutcome outcome) {
        state.setPhase(outcome.phase());
        state.setCurrentIntent(finalIntent.name());
        state.setSlots(mergedSlots);
        state.setPendingAsk(outcome.pendingAsk());
        state.setTurnCount((state.getTurnCount() == null ? 0 : state.getTurnCount()) + 1);

        // last_recommendations only refreshed when this branch produced new items.
        // Empty result lists DO NOT clobber the prior turn's snapshot — keeps COMPARE
        // anchorable across degenerate retrievals.
        if (outcome.newRecItems() != null && !outcome.newRecItems().isEmpty()) {
            LastRecommendationsSnapshot snap = LastRecommendationsSnapshot.from(outcome.newRecItems());
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = objectMapper.convertValue(snap, Map.class);
            state.setLastRecommendations(asMap);
        }

        Instant now = Instant.now();
        if (state.getCreatedAt() == null) {
            state.setCreatedAt(now);
        }
        state.setUpdatedAt(now);

        sessionStateService.save(state);
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private SessionState initialState(String sessionId, Long merchantId) {
        SessionState s = new SessionState();
        s.setId(sessionId);
        s.setMerchantId(merchantId);
        return s;
    }

    // ----------------------------------------------------------------------
    // Inner types
    // ----------------------------------------------------------------------

    /** Result of intent revision pass. */
    record RevisedIntent(IntentEnum intent, Map<String, Object> mergedSlots) {}

    /**
     * Carrier from each branch back to the orchestrator, conveying the reply
     * plus the writeback markers persistState needs.
     *
     * @param reply         the EmotionResult to return to the user
     * @param phase         session_state.phase to write
     * @param pendingAsk    session_state.pending_ask; null clears it
     * @param newRecItems   non-null only for branches that produced fresh recommendations;
     *                      drives last_recommendations writeback (D)
     */
    record BranchOutcome(
            EmotionResult reply,
            String phase,
            String pendingAsk,
            List<RecommendedItem> newRecItems
    ) {}
}
