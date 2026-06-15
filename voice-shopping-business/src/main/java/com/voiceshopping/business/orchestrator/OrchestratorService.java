package com.voiceshopping.business.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.business.agent.ClarifyRuleService;
import com.voiceshopping.business.agent.ClarifyService;
import com.voiceshopping.business.agent.EmotionService;
import com.voiceshopping.business.agent.IntentService;
import com.voiceshopping.business.compliance.ComplianceChecker;
import com.voiceshopping.business.event.VoiceEventPublisher;
import com.voiceshopping.business.memory.ShortTermMemory;
import com.voiceshopping.business.memory.TurnSummarizer;
import com.voiceshopping.business.order.OrderReferenceResolver;
import com.voiceshopping.business.order.OrderService;
import com.voiceshopping.business.order.PendingOrderStore;
import com.voiceshopping.business.perspective.PerspectiveHubService;
import com.voiceshopping.business.rec.ParallelRecommendService;
import com.voiceshopping.business.session.SessionStateService;
import com.voiceshopping.common.dto.agent.ClarifyResult;
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.common.dto.agent.IntentResult;
import com.voiceshopping.common.dto.agent.LastRecommendationsSnapshot;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.order.PendingOrder;
import com.voiceshopping.common.enums.IntentEnum;
import com.voiceshopping.common.event.UserSpokenEvent;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.SessionRepository;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
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
import java.util.Optional;

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

    /** Order confirmation phrases — order matters for clarity, not matching. */
    private static final List<String> NO_PHRASES = List.of(
            "不要", "不行", "不用", "算了", "取消", "等等", "再想想", "先不", "别下");
    private static final List<String> YES_PHRASES = List.of(
            "确认", "下单吧", "就要这个", "就这", "可以", "OK", "ok", "买了", "嗯就这");

    private static final String ORDER_REPLY_ASK_REFERENCE =
            "你想要的是刚才推荐的哪一款？可以说第一款、第二款或者商品名。";
    private static final String ORDER_REPLY_PREVIEW_TEMPLATE =
            "好，帮你准备下单：%s，¥%s，一共 %s 元。确认下单吗？";
    private static final String ORDER_REPLY_CONFIRM_TEMPLATE =
            "下单成功，订单尾号 %s，1-2 天送达。还有想看的吗？";
    private static final String ORDER_REPLY_CANCEL =
            "好的，鸡哥没给你下。想再聊点别的还是换款看看？";
    private static final String ORDER_REPLY_AMBIGUOUS =
            "那你是确认要这款还是不要？";
    private static final String ORDER_REPLY_STOCK_GONE =
            "不好意思，刚才那件被抢走了，要不要看看类似的？";

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
    private final TurnSummarizer turnSummarizer;
    private final OrderService orderService;
    private final PendingOrderStore pendingOrderStore;
    private final OrderReferenceResolver referenceResolver;

    private final boolean perspectiveEnabled;
    private final boolean orderEnabled;

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
                               TurnSummarizer turnSummarizer,
                               OrderService orderService,
                               PendingOrderStore pendingOrderStore,
                               OrderReferenceResolver referenceResolver,
                               MeterRegistry meterRegistry,
                               @Value("${voice-shopping.perspective.enabled:false}") boolean perspectiveEnabled,
                               @Value("${voice-shopping.order.enabled:true}") boolean orderEnabled) {
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
        this.turnSummarizer = turnSummarizer;
        this.orderService = orderService;
        this.pendingOrderStore = pendingOrderStore;
        this.referenceResolver = referenceResolver;
        this.perspectiveEnabled = perspectiveEnabled;
        this.orderEnabled = orderEnabled;

        // Pre-build one Timer per IntentEnum so finally-block lookup is O(1) (D13).
        this.timersByIntent = new EnumMap<>(IntentEnum.class);
        for (IntentEnum intent : IntentEnum.values()) {
            Timer timer = Timer.builder("voice.shopping.orchestrator.handle")
                    .tag("intent", intent.name())
                    .register(meterRegistry);
            this.timersByIntent.put(intent, timer);
        }
        log.info("OrchestratorService initialized: perspectiveEnabled={}, orderEnabled={}",
                perspectiveEnabled, orderEnabled);
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

            // 3. Load session_state (or initialize a fresh one). The current utterance
            //    is passed to IntentService directly — no need to pre-append a USER turn;
            //    a TURN summary will be written at the end of this method instead.
            SessionState state = sessionStateService.load(sessionId)
                    .orElseGet(() -> initialState(sessionId, session.getMerchantId()));

            // 3.5 Phase short-circuit — if we are mid order-confirm, skip IntentService
            //     entirely. handleOrderConfirm returns null only when the pending order
            //     has expired AND the user's utterance can't be resolved to a prior
            //     recommendation; in that case we revert phase=RECOMMEND and fall
            //     through to the normal intent pipeline so the user is not stuck.
            if (orderEnabled && SessionPhase.ORDER_CONFIRM.equals(state.getPhase())) {
                BranchOutcome shortCircuit = handleOrderConfirm(sessionId, userId, state, utterance);
                if (shortCircuit != null) {
                    finalIntent = IntentEnum.ORDER_CONFIRM;
                    return finalizeTurn(sessionId, userId, utterance, state, finalIntent,
                            state.getSlots(), shortCircuit);
                }
                log.info("Order pending expired and reference unresolved for session={} — reverting phase to RECOMMEND",
                        sessionId);
                state.setPhase(SessionPhase.RECOMMEND);
                // Do NOT persist here: the regular pipeline below will overwrite phase
                // based on its own outcome. Persisting twice would be wasted Redis I/O.
            }

            // 4. Intent classification
            IntentResult intent = intentService.classify(sessionId, utterance);
            log.info("Intent classified for session={}: {} (confidence={})",
                    sessionId, intent.intent(), intent.confidence());

            // 5. Apply revision rules (priceDirection anchor / info-sufficient)
            RevisedIntent revised = reviseIntent(intent, state);
            finalIntent = revised.intent();
            log.info("Intent revised for session={}: {} → {}", sessionId, intent.intent(), finalIntent);

            // 6. Dispatch by final intent (each branch returns the BranchOutcome
            //    carrying EmotionResult + branch-specific writeback markers)
            BranchOutcome outcome = dispatch(sessionId, userId, utterance, state, revised);

            return finalizeTurn(sessionId, userId, utterance, state, finalIntent,
                    revised.mergedSlots(), outcome);
        } finally {
            sample.stop(timersByIntent.get(finalIntent));
        }
    }

    /**
     * Shared post-dispatch tail: compliance pass, ASSISTANT/TURN memory append,
     * session_state writeback. Extracted so the order-phase short-circuit and
     * the regular dispatch path go through the same plumbing — same Timer tag
     * semantics, same memory writes, same persistence ordering.
     */
    private EmotionResult finalizeTurn(String sessionId, Long userId, String utterance,
                                       SessionState state, IntentEnum finalIntent,
                                       Map<String, Object> mergedSlots, BranchOutcome outcome) {
        // 7. Compliance pass-through (placeholder)
        EmotionResult safeReply = complianceChecker.ensureCompliant(sessionId, userId, outcome.reply());

        // 8. Append ASSISTANT turn — agent tag carries the final (post-revision)
        //    intent name so downstream consumers can attribute replies.
        Instant now = Instant.now();
        shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                "ASSISTANT", safeReply.speechText(), finalIntent.name(), now));

        // 9. Append TURN summary — compact one-line view of this completed turn,
        //    feeding next-turn IntentService.recent(3). Replaces the previous USER
        //    raw entry to avoid double-counting in the recent window.
        String summary = turnSummarizer.summarize(utterance, finalIntent, safeReply.speechText());
        shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                "TURN", summary, finalIntent.name(), now));

        // 10. Persist final session_state
        persistState(state, finalIntent, mergedSlots, outcome);

        return safeReply;
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
            case ORDER_CONFIRM          -> runOrderConfirm(sessionId, userId, state, utterance);
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

    BranchOutcome runOrderConfirm(String sessionId, Long userId, SessionState state, String utterance) {
        if (!orderEnabled) {
            // Rollback fallback — keep the legacy placeholder for the
            // toggle-off path, no Redis / DB writes happen.
            return new BranchOutcome(
                    new EmotionResult(ORDER_CONFIRM_REPLY, List.of()),
                    SessionPhase.ORDER_CONFIRM,
                    null,
                    null);
        }
        BranchOutcome outcome = handleOrderConfirm(sessionId, userId, state, utterance);
        if (outcome != null) {
            return outcome;
        }
        // First-turn ORDER_CONFIRM with no resolvable reference (state.lastRecommendations
        // empty or utterance unrelated) — ask EmotionAgent to clarify which item,
        // staying in ORDER_CONFIRM so the next turn keeps the short-circuit path.
        return new BranchOutcome(
                new EmotionResult(ORDER_REPLY_ASK_REFERENCE, List.of()),
                SessionPhase.ORDER_CONFIRM,
                ORDER_REPLY_ASK_REFERENCE,
                null);
    }

    /**
     * Order-confirm sub state machine.
     * <p>
     * Returns {@code null} only when there is no pending order AND the user's
     * utterance can't be resolved to a product from {@code lastRecommendations} —
     * the caller (handle's short-circuit branch) interprets this as
     * "pending expired, fall through to normal intent classification".
     * <p>
     * Every other path returns a populated {@link BranchOutcome}: confirm
     * success, cancel, ambiguous re-prompt, stock-gone friendly fallback,
     * or new preview.
     */
    BranchOutcome handleOrderConfirm(String sessionId, Long userId, SessionState state, String utterance) {
        PendingOrder pending = pendingOrderStore.get(sessionId);

        if (pending != null) {
            // NO check first — "好像不太对" must NOT count as a YES.
            if (containsNo(utterance)) {
                orderService.cancel(sessionId);
                return new BranchOutcome(
                        new EmotionResult(ORDER_REPLY_CANCEL, List.of()),
                        SessionPhase.RECOMMEND,
                        null,
                        null);
            }
            if (containsYes(utterance)) {
                try {
                    OrderRecord order = orderService.confirm(sessionId);
                    String tail = order.getOrderNo() == null
                            ? "------"
                            : order.getOrderNo().substring(0, Math.min(6, order.getOrderNo().length()));
                    String reply = String.format(ORDER_REPLY_CONFIRM_TEMPLATE, tail);
                    return new BranchOutcome(
                            new EmotionResult(reply, List.of()),
                            SessionPhase.ENDED,
                            null,
                            null);
                } catch (IllegalStateException e) {
                    // Stock drained between preview and confirm — friendly fallback;
                    // preview is implicitly invalidated by the failed decrement, so
                    // we also remove the Redis pending entry to avoid the user being
                    // looped on a permanently-failing confirm.
                    if ("库存不足".equals(e.getMessage())) {
                        log.info("confirm fell through due to stock exhaustion: sessionId={}", sessionId);
                        orderService.cancel(sessionId);
                        return new BranchOutcome(
                                new EmotionResult(ORDER_REPLY_STOCK_GONE, List.of()),
                                SessionPhase.RECOMMEND,
                                null,
                                null);
                    }
                    throw e;
                }
            }
            // Neither yes nor no — re-ask without changing phase.
            return new BranchOutcome(
                    new EmotionResult(ORDER_REPLY_AMBIGUOUS, List.of()),
                    SessionPhase.ORDER_CONFIRM,
                    ORDER_REPLY_AMBIGUOUS,
                    null);
        }

        // No pending: try to resolve a reference from the last recommendations.
        LastRecommendationsSnapshot snap = readLastRecommendations(state);
        if (snap == null) {
            return null;
        }
        Optional<Long> pidOpt = referenceResolver.resolve(snap, utterance);
        if (pidOpt.isEmpty()) {
            return null;
        }

        try {
            PendingOrder po = orderService.preview(sessionId, userId, pidOpt.get(), 1);
            String reply = String.format(ORDER_REPLY_PREVIEW_TEMPLATE,
                    po.productName(), po.unitPrice().toPlainString(), po.totalAmount().toPlainString());
            return new BranchOutcome(
                    new EmotionResult(reply, List.of()),
                    SessionPhase.ORDER_CONFIRM,
                    reply,
                    null);
        } catch (IllegalStateException e) {
            // Stock unavailable at preview time — encourage the user to look elsewhere.
            if ("库存不足".equals(e.getMessage())) {
                return new BranchOutcome(
                        new EmotionResult(ORDER_REPLY_STOCK_GONE, List.of()),
                        SessionPhase.RECOMMEND,
                        null,
                        null);
            }
            throw e;
        }
    }

    /**
     * Match a user utterance against the negative-confirmation phrase list.
     * Single-character markers like "好" / "对" are deliberately NOT in
     * {@link #YES_PHRASES} — too high false-positive rate ("好像不太对").
     */
    boolean containsNo(String utterance) {
        if (utterance == null) {
            return false;
        }
        for (String phrase : NO_PHRASES) {
            if (utterance.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match a user utterance against the positive-confirmation phrase list.
     * NO is checked first so "好像不太对" — which contains "好" — falls
     * through to the negative branch.
     */
    boolean containsYes(String utterance) {
        if (utterance == null) {
            return false;
        }
        if (containsNo(utterance)) {
            return false;
        }
        for (String phrase : YES_PHRASES) {
            if (utterance.contains(phrase)) {
                return true;
            }
        }
        return false;
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
