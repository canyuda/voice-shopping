package com.voiceshopping.business.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.business.agent.ClarifyRuleService;
import com.voiceshopping.business.agent.ClarifyService;
import com.voiceshopping.business.agent.EmotionService;
import com.voiceshopping.business.agent.EmotionStreamingService;
import com.voiceshopping.business.agent.IntentService;
import com.voiceshopping.business.compliance.ComplianceChecker;
import com.voiceshopping.business.event.VoiceEventPublisher;
import com.voiceshopping.business.memory.ShortTermMemory;
import com.voiceshopping.business.memory.TurnSummarizer;
import com.voiceshopping.ai.tts.TTSService;
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
import com.voiceshopping.common.enums.IntentEnum;
import com.voiceshopping.common.event.UserSpokenEvent;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.SessionRepository;
import com.voiceshopping.infrastructure.repository.entity.Session;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link OrchestratorService}. Each test maps to a
 * spec scenario or a tasks.md test case from the add-orchestrator-service change.
 * Assertions use hand-computed expected values, never re-derived from production
 * code — see grader-gaming notes in the change PR description.
 */
class OrchestratorServiceTest {

    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";
    private static final Long USER_ID = 100L;
    private static final Long MERCHANT_ID = 1L;

    private SessionRepository sessionRepository;
    private SessionStateService sessionStateService;
    private ShortTermMemory shortTermMemory;
    private VoiceEventPublisher voiceEventPublisher;
    private IntentService intentService;
    private ClarifyService clarifyService;
    private ClarifyRuleService clarifyRuleService;
    private ParallelRecommendService parallelRecommendService;
    private PerspectiveHubService perspectiveHubService;
    private EmotionService emotionService;
    private ComplianceChecker complianceChecker;
    private ObjectMapper objectMapper;
    private TurnSummarizer turnSummarizer;
    private OrderService orderService;
    private PendingOrderStore pendingOrderStore;
    private OrderReferenceResolver referenceResolver;
    private EmotionStreamingService emotionStreamingService;
    private TTSService ttsService;

    @BeforeEach
    void setup() {
        sessionRepository = mock(SessionRepository.class);
        sessionStateService = mock(SessionStateService.class);
        shortTermMemory = mock(ShortTermMemory.class);
        voiceEventPublisher = mock(VoiceEventPublisher.class);
        intentService = mock(IntentService.class);
        clarifyService = mock(ClarifyService.class);
        clarifyRuleService = mock(ClarifyRuleService.class);
        parallelRecommendService = mock(ParallelRecommendService.class);
        perspectiveHubService = mock(PerspectiveHubService.class);
        emotionService = mock(EmotionService.class);
        complianceChecker = mock(ComplianceChecker.class);
        // Default: compliance is a no-op pass-through. Real masking is tested in ComplianceCheckerTest.
        when(complianceChecker.ensureCompliant(anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        objectMapper = new ObjectMapper();
        turnSummarizer = new TurnSummarizer();
        orderService = mock(OrderService.class);
        pendingOrderStore = mock(PendingOrderStore.class);
        referenceResolver = mock(OrderReferenceResolver.class);
        emotionStreamingService = mock(EmotionStreamingService.class);
        ttsService = mock(TTSService.class);

        // Default: session exists, state is empty.
        Session session = new Session();
        session.setId(SESSION_ID);
        session.setMerchantId(MERCHANT_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.empty());
        // Default rule: nothing missing — keeps tests focused on intent semantics
        // unless a test re-stubs.
        when(clarifyRuleService.missingSlots(any(), any())).thenReturn(List.of());
    }

    private OrchestratorService buildOrchestrator(boolean perspectiveEnabled) {
        return buildOrchestrator(perspectiveEnabled, false);
    }

    /**
     * Order-aware overload — turn the {@code voice-shopping.order.enabled}
     * toggle on for tests that exercise the ORDER_CONFIRM phase short-circuit
     * or the {@code handleOrderConfirm} branch. Existing tests pass {@code false}
     * to retain the legacy placeholder reply behaviour.
     */
    private OrchestratorService buildOrchestrator(boolean perspectiveEnabled, boolean orderEnabled) {
        return new OrchestratorService(
                sessionRepository, sessionStateService, shortTermMemory, voiceEventPublisher,
                intentService, clarifyService, clarifyRuleService,
                parallelRecommendService, perspectiveHubService, emotionService,
                complianceChecker, objectMapper, turnSummarizer,
                orderService, pendingOrderStore, referenceResolver,
                emotionStreamingService, ttsService,
                new SimpleMeterRegistry(), perspectiveEnabled, orderEnabled,
                50L);
    }

    // ---------------------------------------------------------------------
    // 7.2 — handle does not create sessions
    // ---------------------------------------------------------------------

    @Test
    void sessionNotFound_throwsNotFoundException() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> buildOrchestrator(false).handle(SESSION_ID, USER_ID, "你好"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(SESSION_ID);

        // No state writes, no memory writes, no events published.
        verify(sessionStateService, never()).save(any());
        verify(shortTermMemory, never()).append(anyString(), any());
    }

    // ---------------------------------------------------------------------
    // 7.3 — Revision rule ① (priceDirection anchor)
    // ---------------------------------------------------------------------

    @Test
    void rule1_priceDirectionAnchor_withPriorRec_forcesCompare() {
        // Given: prior rec, current intent says RECOMMENDATION, slots carry "cheaper"
        SessionState prior = new SessionState();
        prior.setId(SESSION_ID);
        prior.setLastRecommendations(snapshotMap(
                List.of(item(1L, "A", "479"), item(2L, "B", "599"))));
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(prior));

        Map<String, Object> slots = new HashMap<>();
        slots.put("priceDirection", "cheaper");
        when(intentService.classify(SESSION_ID, "便宜点"))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION, slots, 0.9));
        stubRecommendation(List.of(item(3L, "C", "300")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "便宜点");

        // Then: ParallelRecommend was invoked with COMPARE-style slots
        // (excludeProductIds + budget reset). We don't assert on the exact value of
        // the slots map here — that's covered by 7.7.
        ArgumentCaptor<Map<String, Object>> slotsCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(parallelRecommendService).recommend(eq(SESSION_ID), eq(USER_ID), eq("便宜点"), slotsCaptor.capture());
        // The COMPARE branch always sets excludeProductIds; RECOMMENDATION never does.
        assertThat(slotsCaptor.getValue().get("excludeProductIds")).isNotNull();

        // Persisted intent name reflects the revised intent.
        SessionState saved = captureSavedState();
        assertThat(saved.getCurrentIntent()).isEqualTo("PRODUCT_COMPARE");
    }

    @Test
    void rule1_noPriorRec_doesNotRevise() {
        // No state.lastRecommendations + cheaper slot. LLM gives RECOMMENDATION.
        Map<String, Object> slots = new HashMap<>();
        slots.put("priceDirection", "cheaper");
        slots.put("category", "跑鞋");
        when(intentService.classify(SESSION_ID, "便宜点"))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION, slots, 0.9));
        stubClarifyReady();
        stubRecommendation(List.of(item(1L, "A", "100")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "便宜点");

        // PRODUCT_COMPARE never sets pendingAsk via Clarify; RECOMMENDATION goes through Clarify.
        verify(clarifyService).decide(eq(SESSION_ID), anyString(), any());
        SessionState saved = captureSavedState();
        assertThat(saved.getCurrentIntent()).isEqualTo("PRODUCT_RECOMMENDATION");
    }

    @Test
    void rule1_unknownDirection_doesNotRevise() {
        // Prior rec exists, but priceDirection value is not in the whitelist.
        SessionState prior = new SessionState();
        prior.setId(SESSION_ID);
        prior.setLastRecommendations(snapshotMap(List.of(item(1L, "A", "100"))));
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(prior));

        Map<String, Object> slots = new HashMap<>();
        slots.put("priceDirection", "lower"); // not in {"cheaper","expensive"}
        slots.put("category", "跑鞋");
        when(intentService.classify(SESSION_ID, "再换一款"))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION, slots, 0.9));
        stubClarifyReady();
        stubRecommendation(List.of(item(2L, "B", "200")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "再换一款");

        SessionState saved = captureSavedState();
        assertThat(saved.getCurrentIntent()).isEqualTo("PRODUCT_RECOMMENDATION");
    }

    // ---------------------------------------------------------------------
    // 7.4 — Revision rule ② (info-sufficient promotes CLARIFY → RECOMMENDATION)
    // ---------------------------------------------------------------------

    @Test
    void rule2_clarifyButSlotsComplete_promotesToRecommendation() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        slots.put("budget", 500);
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.CLARIFY_NEEDED, slots, 0.7));
        when(clarifyRuleService.missingSlots(eq("跑鞋"), any())).thenReturn(List.of()); // info-sufficient
        stubClarifyReady();
        stubRecommendation(List.of(item(1L, "A", "400")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "想买双跑鞋 500 内");

        SessionState saved = captureSavedState();
        assertThat(saved.getCurrentIntent()).isEqualTo("PRODUCT_RECOMMENDATION");
    }

    @Test
    void rule2_clarifyAndSlotsIncomplete_doesNotPromote() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.CLARIFY_NEEDED, new HashMap<>(), 0.7));
        when(clarifyRuleService.missingSlots(any(), any()))
                .thenReturn(List.of("category")); // missing → not promoted
        when(clarifyService.decide(eq(SESSION_ID), anyString(), any()))
                .thenReturn(ClarifyResult.ask("您想买啥类目的？", List.of("category")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "随便看看");

        SessionState saved = captureSavedState();
        assertThat(saved.getCurrentIntent()).isEqualTo("CLARIFY_NEEDED");
        assertThat(saved.getPendingAsk()).isEqualTo("您想买啥类目的？");
        verify(parallelRecommendService, never()).recommend(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // 7.5 — Six branches each produce a non-null EmotionResult and persist state
    // ---------------------------------------------------------------------

    @Test
    void orderConfirm_returnsFixedReply_andPersistsOrderConfirmPhase() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.ORDER_CONFIRM, Map.of(), 0.95));

        EmotionResult reply = buildOrchestrator(false).handle(SESSION_ID, USER_ID, "下单");

        assertThat(reply.speechText()).isEqualTo("好，给你下单（完整下单逻辑后续补）");
        assertThat(reply.displayBlocks()).isEmpty();
        SessionState saved = captureSavedState();
        assertThat(saved.getPhase()).isEqualTo("ORDER_CONFIRM");
        assertThat(saved.getCurrentIntent()).isEqualTo("ORDER_CONFIRM");
    }

    @Test
    void outOfScope_returnsFixedReply_andPersistsIntentPhase() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.OUT_OF_SCOPE, Map.of(), 0.95));

        EmotionResult reply = buildOrchestrator(false).handle(SESSION_ID, USER_ID, "今天天气怎么样");

        assertThat(reply.speechText())
                .isEqualTo("只负责帮你挑商品，这个问题回头可以找客服处理哈。我们继续聊想买什么？");
        assertThat(reply.displayBlocks()).isEmpty();
        SessionState saved = captureSavedState();
        assertThat(saved.getPhase()).isEqualTo("INTENT");
    }

    // ---------------------------------------------------------------------
    // 7.6 — CLARIFY ASK skips ParallelRecommend; READY invokes it
    // ---------------------------------------------------------------------

    @Test
    void clarifyAsk_skipsRecommendation() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.CLARIFY_NEEDED, Map.of("category", "跑鞋"), 0.7));
        when(clarifyRuleService.missingSlots(any(), any())).thenReturn(List.of("scenario"));
        when(clarifyService.decide(eq(SESSION_ID), anyString(), any()))
                .thenReturn(ClarifyResult.ask("跑塑胶跑道还是水泥路？", List.of("scenario")));

        EmotionResult reply = buildOrchestrator(false).handle(SESSION_ID, USER_ID, "想买跑鞋");

        assertThat(reply.speechText()).isEqualTo("跑塑胶跑道还是水泥路？");
        verify(parallelRecommendService, never()).recommend(any(), any(), any(), any());
        SessionState saved = captureSavedState();
        assertThat(saved.getPhase()).isEqualTo("CLARIFY");
        assertThat(saved.getPendingAsk()).isEqualTo("跑塑胶跑道还是水泥路？");
    }

    @Test
    void clarifyReady_callsParallelRecommend() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.CLARIFY_NEEDED, Map.of("category", "跑鞋"), 0.7));
        // Rule② will promote; Clarify also returns READY anyway.
        stubClarifyReady();
        stubRecommendation(List.of(item(1L, "A", "100")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "想买双跑鞋");

        verify(parallelRecommendService, times(1)).recommend(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // 7.7 — PRODUCT_COMPARE slot rewriting
    // ---------------------------------------------------------------------

    @Test
    void compareCheaper_setsBudgetToMaxTimes08_andExcludesPriorIds() {
        // Prior turn: items priced [479, 599, 379] → max=599
        SessionState prior = priorStateWith(List.of(
                item(8821L, "A", "479"),
                item(8822L, "B", "599"),
                item(8823L, "C", "379")));
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(prior));

        Map<String, Object> slots = new HashMap<>();
        slots.put("priceDirection", "cheaper");
        when(intentService.classify(SESSION_ID, "便宜点")).thenReturn(
                new IntentResult(IntentEnum.PRODUCT_COMPARE, slots, 0.9));
        stubRecommendation(List.of(item(9L, "X", "200")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "便宜点");

        Map<String, Object> sentSlots = captureRecommendSlots();
        // 599 * 0.8 = 479.2 (hand-computed)
        assertThat((BigDecimal) sentSlots.get("budget")).isEqualByComparingTo("479.2");
        // priceMin must NOT leak into cheaper path
        assertThat(sentSlots).doesNotContainKey("priceMin");
        @SuppressWarnings("unchecked")
        List<Object> excludeIds = (List<Object>) sentSlots.get("excludeProductIds");
        assertThat(excludeIds).containsExactly(8821L, 8822L, 8823L);
    }

    @Test
    void compareExpensive_setsPriceMinToMinTimes12_andLeavesBudgetAlone() {
        SessionState prior = priorStateWith(List.of(
                item(8821L, "A", "479"),
                item(8822L, "B", "599"),
                item(8823L, "C", "379")));
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(prior));

        Map<String, Object> slots = new HashMap<>();
        slots.put("priceDirection", "expensive");
        slots.put("budget", 1000); // user-given upper bound — must be preserved
        when(intentService.classify(SESSION_ID, "贵点")).thenReturn(
                new IntentResult(IntentEnum.PRODUCT_COMPARE, slots, 0.9));
        stubRecommendation(List.of(item(9L, "X", "800")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "贵点");

        Map<String, Object> sentSlots = captureRecommendSlots();
        // 379 * 1.2 = 454.8 (hand-computed)
        assertThat((BigDecimal) sentSlots.get("priceMin")).isEqualByComparingTo("454.8");
        assertThat(sentSlots.get("budget")).isEqualTo(1000);
        @SuppressWarnings("unchecked")
        List<Object> excludeIds2 = (List<Object>) sentSlots.get("excludeProductIds");
        assertThat(excludeIds2).containsExactly(8821L, 8822L, 8823L);
    }

    // ---------------------------------------------------------------------
    // 7.8 — COMPARE without prior rec falls back to RECOMMENDATION
    // ---------------------------------------------------------------------

    @Test
    void compareWithoutPriorRec_fallsBackToRecommendation() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("priceDirection", "cheaper");
        when(intentService.classify(SESSION_ID, "便宜点")).thenReturn(
                new IntentResult(IntentEnum.PRODUCT_COMPARE, slots, 0.9));
        stubClarifyReady();
        stubRecommendation(List.of(item(1L, "A", "100")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "便宜点");

        // Falls through to the RECOMMENDATION pipeline → ClarifyService is consulted.
        verify(clarifyService).decide(eq(SESSION_ID), anyString(), any());
        Map<String, Object> sentSlots = captureRecommendSlots();
        // No COMPARE-specific slot rewrites should have happened.
        assertThat(sentSlots).doesNotContainKey("excludeProductIds");
    }

    // ---------------------------------------------------------------------
    // 7.9 — Perspective switch
    // ---------------------------------------------------------------------

    @Test
    void perspectiveOff_perspectiveHubNotCalled() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION, slots, 0.9));
        stubClarifyReady();
        stubRecommendation(List.of(item(1L, "A", "100")));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "想买跑鞋");

        verify(perspectiveHubService, never()).discuss(any(), any(), any());
    }

    @Test
    void perspectiveOn_appendsPanelToUtteranceForEmotion() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION, slots, 0.9));
        stubClarifyReady();
        stubRecommendation(List.of(item(1L, "A", "100")));
        when(perspectiveHubService.discuss(eq(SESSION_ID), anyString(), any()))
                .thenReturn("价格顾问：xxx\n专业用户：yyy\n入门买家：zzz");

        buildOrchestrator(true).handle(SESSION_ID, USER_ID, "想买跑鞋");

        ArgumentCaptor<String> utteranceCaptor = ArgumentCaptor.forClass(String.class);
        verify(emotionService).wrap(eq(SESSION_ID), utteranceCaptor.capture(), anyString(), any());
        assertThat(utteranceCaptor.getValue())
                .contains("想买跑鞋")
                .contains("[多视角点评]")
                .contains("价格顾问：xxx");
    }

    // ---------------------------------------------------------------------
    // 7.10 — Side effects: event published, memory ordered, turnCount + 1
    // ---------------------------------------------------------------------

    @Test
    void everyCall_publishesUserSpokenEvent_andTurnsAreOrdered_andTurnCountIncrements() {
        SessionState prior = new SessionState();
        prior.setId(SESSION_ID);
        prior.setMerchantId(MERCHANT_ID);
        prior.setTurnCount(3);
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(prior));

        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.OUT_OF_SCOPE, Map.of(), 0.9));

        buildOrchestrator(false).handle(SESSION_ID, USER_ID, "你好");

        verify(voiceEventPublisher, atLeastOnce()).publish(any(UserSpokenEvent.class));

        // After the change: each turn appends ASSISTANT then TURN — no leading USER row.
        ArgumentCaptor<ShortTermMemory.Turn> turnCaptor =
                ArgumentCaptor.forClass(ShortTermMemory.Turn.class);
        verify(shortTermMemory, times(2)).append(eq(SESSION_ID), turnCaptor.capture());
        List<ShortTermMemory.Turn> turns = turnCaptor.getAllValues();
        assertThat(turns.get(0).role()).isEqualTo("ASSISTANT");
        assertThat(turns.get(0).agent()).isEqualTo("OUT_OF_SCOPE");
        assertThat(turns.get(1).role()).isEqualTo("TURN");
        assertThat(turns.get(1).agent()).isEqualTo("OUT_OF_SCOPE");
        // TURN summary content carries the [INTENT] prefix and original user utterance.
        assertThat(turns.get(1).content())
                .startsWith("[OUT_OF_SCOPE]")
                .contains("你好");

        // turnCount monotonic: 3 → 4
        SessionState saved = captureSavedState();
        assertThat(saved.getTurnCount()).isEqualTo(4);
    }

    @Test
    void dispatchThrows_doesNotAppendAssistantOrTurn() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION,
                        Map.of("category", "跑鞋"), 0.9));
        when(clarifyService.decide(eq(SESSION_ID), anyString(), any()))
                .thenThrow(new RuntimeException("clarify boom"));

        assertThatThrownBy(() -> buildOrchestrator(false).handle(SESSION_ID, USER_ID, "想买跑鞋"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("clarify boom");

        // No memory writes when dispatch fails before the ASSISTANT/TURN appends.
        verify(shortTermMemory, never()).append(anyString(), any());
        verify(sessionStateService, never()).save(any());
    }

    // ---------------------------------------------------------------------
    // 7.11 — Chitchat 直接从 ChitchatReplyPool 抽取兜底文案，不调 EmotionAgent
    // ---------------------------------------------------------------------

    @Test
    void chitchat_skipsEmotionAgentAndReturnsPoolReply() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.CHITCHAT, Map.of(), 0.9));

        EmotionResult reply = buildOrchestrator(false).handle(SESSION_ID, USER_ID, "讲个笑话");

        // 不调 EmotionAgent
        verify(emotionService, never()).wrap(anyString(), anyString(), anyString(), any());
        // 返回的文案应在 ChitchatReplyPool 池内
        assertThat(com.voiceshopping.business.agent.ChitchatReplyPool.repliesSnapshot())
                .contains(reply.speechText());
    }

    @Test
    void chitchat_doesNotIncludeProducts() {
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.CHITCHAT, Map.of(), 0.9));

        EmotionResult reply = buildOrchestrator(false).handle(SESSION_ID, USER_ID, "讲个笑话");

        assertThat(reply.displayBlocks()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static RecommendedItem item(long id, String name, String price) {
        return new RecommendedItem(id, name, new BigDecimal(price), "r", 0.9, Map.of());
    }

    private void stubClarifyReady() {
        when(clarifyService.decide(eq(SESSION_ID), anyString(), any()))
                .thenReturn(ClarifyResult.ready());
    }

    private void stubRecommendation(List<RecommendedItem> items) {
        when(parallelRecommendService.recommend(eq(SESSION_ID), eq(USER_ID), anyString(), any()))
                .thenReturn(new RecommendResult(items, "professional"));
        when(emotionService.wrap(eq(SESSION_ID), anyString(), anyString(), any()))
                .thenReturn(new EmotionResult("好，给你挑了几款", items));
    }

    private SessionState priorStateWith(List<RecommendedItem> items) {
        SessionState s = new SessionState();
        s.setId(SESSION_ID);
        s.setMerchantId(MERCHANT_ID);
        s.setLastRecommendations(snapshotMap(items));
        return s;
    }

    /** Convert items → snapshot Map exactly as persistState does, so tests share that path. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotMap(List<RecommendedItem> items) {
        return objectMapper.convertValue(LastRecommendationsSnapshot.from(items), Map.class);
    }

    private SessionState captureSavedState() {
        ArgumentCaptor<SessionState> captor = ArgumentCaptor.forClass(SessionState.class);
        verify(sessionStateService).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureRecommendSlots() {
        ArgumentCaptor<Map<String, Object>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(parallelRecommendService).recommend(eq(SESSION_ID), eq(USER_ID), anyString(), captor.capture());
        return captor.getValue();
    }
}
