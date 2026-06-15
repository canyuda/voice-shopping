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
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.common.dto.agent.IntentResult;
import com.voiceshopping.common.dto.agent.LastRecommendationsSnapshot;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.order.PendingOrder;
import com.voiceshopping.common.enums.IntentEnum;
import com.voiceshopping.infrastructure.repository.OrderRecordRepository;
import com.voiceshopping.infrastructure.repository.SessionRepository;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
import com.voiceshopping.infrastructure.repository.entity.Session;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the order-confirm sub state machine in {@link OrchestratorService}.
 * Covers:
 *   - phase short-circuit + YES success
 *   - phase short-circuit + NO cancel
 *   - phase short-circuit + ambiguous → re-ask
 *   - phase short-circuit + YES + stock exhausted → friendly fallback
 *   - first-turn ORDER_CONFIRM intent → preview
 *   - phase short-circuit + pending expired + reference unresolved → fall through
 *
 * The Orchestrator's existing tests are in {@code OrchestratorServiceTest};
 * this class focuses purely on the new ORDER_CONFIRM behavior.
 */
class OrchestratorOrderConfirmTest {

    private static final String SESSION_ID = "sess-1";
    private static final Long USER_ID = 100L;
    private static final Long MERCHANT_ID = 1L;
    private static final Long PRODUCT_ID = 8821L;

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
        when(complianceChecker.ensureCompliant(anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        objectMapper = new ObjectMapper();
        turnSummarizer = new TurnSummarizer();
        orderService = mock(OrderService.class);
        pendingOrderStore = mock(PendingOrderStore.class);
        referenceResolver = mock(OrderReferenceResolver.class);

        Session session = new Session();
        session.setId(SESSION_ID);
        session.setMerchantId(MERCHANT_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    private OrchestratorService buildOrchestrator() {
        return new OrchestratorService(
                sessionRepository, sessionStateService, shortTermMemory, voiceEventPublisher,
                intentService, clarifyService, clarifyRuleService,
                parallelRecommendService, perspectiveHubService, emotionService,
                complianceChecker, objectMapper, turnSummarizer,
                orderService, pendingOrderStore, referenceResolver,
                new SimpleMeterRegistry(),
                /* perspectiveEnabled */ false,
                /* orderEnabled       */ true);
    }

    private SessionState orderConfirmState() {
        SessionState state = new SessionState();
        state.setId(SESSION_ID);
        state.setMerchantId(MERCHANT_ID);
        state.setPhase("ORDER_CONFIRM");
        return state;
    }

    private SessionState recommendStateWithLast(int n) {
        SessionState state = new SessionState();
        state.setId(SESSION_ID);
        state.setMerchantId(MERCHANT_ID);
        state.setPhase("RECOMMEND");
        // Build a snapshot with `n` items, productId = (i+1)*10.
        List<RecommendedItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            long pid = (i + 1) * 10L;
            items.add(new RecommendedItem(pid, "Item " + pid,
                    new BigDecimal(100 + i * 50), "r", 0.9, Map.of()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotMap = objectMapper.convertValue(
                LastRecommendationsSnapshot.from(items), Map.class);
        state.setLastRecommendations(snapshotMap);
        return state;
    }

    private PendingOrder samplePending() {
        return new PendingOrder(
                SESSION_ID, USER_ID, MERCHANT_ID, PRODUCT_ID,
                "Asics GEL", "SKU-8821", 1,
                new BigDecimal("479.00"), new BigDecimal("479.00"));
    }

    // -----------------------------------------------------------------------
    // 1. Short-circuit + YES → confirm + ENDED
    // -----------------------------------------------------------------------
    @Test
    void shortCircuit_yes_callsConfirmAndEndsSession() {
        SessionState state = orderConfirmState();
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());
        OrderRecord saved = new OrderRecord();
        saved.setOrderNo("abcdef0123456789abcdef0123456789");
        when(orderService.confirm(SESSION_ID)).thenReturn(saved);

        EmotionResult result = buildOrchestrator().handle(SESSION_ID, USER_ID, "确认下单");

        assertThat(result.speechText()).startsWith("下单成功，订单尾号 abcdef");
        verify(orderService).confirm(SESSION_ID);
        // IntentService MUST be skipped on the short-circuit path.
        verify(intentService, never()).classify(anyString(), anyString());
        // phase persisted as ENDED.
        SessionState saved1 = captureSavedState();
        assertThat(saved1.getPhase()).isEqualTo("ENDED");
    }

    // -----------------------------------------------------------------------
    // 2. Short-circuit + NO → cancel + RECOMMEND
    // -----------------------------------------------------------------------
    @Test
    void shortCircuit_no_cancelsAndRevertsToRecommend() {
        SessionState state = orderConfirmState();
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());

        EmotionResult result = buildOrchestrator().handle(SESSION_ID, USER_ID, "算了不要了");

        assertThat(result.speechText()).contains("鸡哥没给你下");
        verify(orderService).cancel(SESSION_ID);
        verify(orderService, never()).confirm(any());
        verify(intentService, never()).classify(anyString(), anyString());
        assertThat(captureSavedState().getPhase()).isEqualTo("RECOMMEND");
    }

    // -----------------------------------------------------------------------
    // 3. Short-circuit + ambiguous → re-ask, phase stays ORDER_CONFIRM
    // -----------------------------------------------------------------------
    @Test
    void shortCircuit_ambiguous_reasksWithoutPhaseChange() {
        SessionState state = orderConfirmState();
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());

        EmotionResult result = buildOrchestrator().handle(SESSION_ID, USER_ID, "嗯，这个怎么样");

        assertThat(result.speechText()).contains("确认要这款还是不要");
        verify(orderService, never()).confirm(any());
        verify(orderService, never()).cancel(any());
        assertThat(captureSavedState().getPhase()).isEqualTo("ORDER_CONFIRM");
    }

    // -----------------------------------------------------------------------
    // 4. Short-circuit + YES + stock exhausted → friendly fallback
    // -----------------------------------------------------------------------
    @Test
    void shortCircuit_yes_withStockGone_friendlyFallback() {
        SessionState state = orderConfirmState();
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());
        when(orderService.confirm(SESSION_ID))
                .thenThrow(new IllegalStateException("库存不足"));

        EmotionResult result = buildOrchestrator().handle(SESSION_ID, USER_ID, "确认");

        assertThat(result.speechText()).contains("被抢走了");
        assertThat(captureSavedState().getPhase()).isEqualTo("RECOMMEND");
        // We explicitly call cancel to clear the now-invalid pending entry.
        verify(orderService).cancel(SESSION_ID);
    }

    // -----------------------------------------------------------------------
    // 5. First-turn ORDER_CONFIRM intent → resolve + preview
    // -----------------------------------------------------------------------
    @Test
    void firstTurnOrderConfirmIntent_triggersPreview() {
        // No pending yet — this is the first time we've seen ORDER_CONFIRM.
        SessionState state = recommendStateWithLast(3);
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(null);

        // Intent classifier says ORDER_CONFIRM.
        when(intentService.classify(eq(SESSION_ID), anyString()))
                .thenReturn(new IntentResult(IntentEnum.ORDER_CONFIRM, Map.of(), 0.9));

        // Resolver picks index 1 (productId=20).
        when(referenceResolver.resolve(any(), eq("我要第二款"))).thenReturn(Optional.of(20L));

        // Preview returns a pending order.
        PendingOrder po = new PendingOrder(
                SESSION_ID, USER_ID, MERCHANT_ID, 20L,
                "Item 20", "SKU-20", 1,
                new BigDecimal("150"), new BigDecimal("150"));
        when(orderService.preview(SESSION_ID, USER_ID, 20L, 1)).thenReturn(po);

        EmotionResult result = buildOrchestrator().handle(SESSION_ID, USER_ID, "我要第二款");

        assertThat(result.speechText()).contains("Item 20").contains("确认下单吗");
        verify(orderService).preview(SESSION_ID, USER_ID, 20L, 1);
        assertThat(captureSavedState().getPhase()).isEqualTo("ORDER_CONFIRM");
    }

    // -----------------------------------------------------------------------
    // 6. Pending expired + reference unresolved → fall through to IntentService
    // -----------------------------------------------------------------------
    @Test
    void pendingExpired_unresolved_fallsThroughToIntentClassification() {
        SessionState state = orderConfirmState();
        // last_recommendations populated so resolver has something to work with.
        SessionState seed = recommendStateWithLast(3);
        state.setLastRecommendations(seed.getLastRecommendations());
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(null);
        when(referenceResolver.resolve(any(), anyString())).thenReturn(Optional.empty());

        // Now IntentService is invoked because we fall through.
        when(intentService.classify(eq(SESSION_ID), eq("再给我推荐点贵的")))
                .thenReturn(new IntentResult(IntentEnum.PRODUCT_RECOMMENDATION, Map.of(), 0.9));
        // Stub clarify+rec so the fall-through path completes.
        when(clarifyRuleService.missingSlots(any(), any())).thenReturn(List.of());
        when(clarifyService.decide(eq(SESSION_ID), anyString(), any()))
                .thenReturn(com.voiceshopping.common.dto.agent.ClarifyResult.ready());
        when(parallelRecommendService.recommend(eq(SESSION_ID), eq(USER_ID), anyString(), any()))
                .thenReturn(new RecommendResult(List.of(), "professional"));
        when(emotionService.wrap(eq(SESSION_ID), anyString(), any()))
                .thenReturn(new EmotionResult("好，给你挑了几款", List.of()));

        EmotionResult result = buildOrchestrator().handle(SESSION_ID, USER_ID, "再给我推荐点贵的");

        verify(intentService, times(1)).classify(eq(SESSION_ID), eq("再给我推荐点贵的"));
        verify(orderService, never()).preview(any(), any(), any(), anyInt());
        verify(orderService, never()).confirm(any());
        assertThat(result).isNotNull();
    }

    private SessionState captureSavedState() {
        ArgumentCaptor<SessionState> captor = ArgumentCaptor.forClass(SessionState.class);
        verify(sessionStateService).save(captor.capture());
        return captor.getValue();
    }
}
