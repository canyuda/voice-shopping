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
import com.voiceshopping.common.dto.agent.StreamChunk;
import com.voiceshopping.common.dto.order.PendingOrder;
import com.voiceshopping.common.enums.IntentEnum;
import com.voiceshopping.common.event.UserSpokenEvent;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.SessionRepository;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
import com.voiceshopping.infrastructure.repository.entity.Session;
import com.voiceshopping.ai.tts.SentenceAggregator;
import com.voiceshopping.ai.tts.TTSService;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
            "好的，没给你下。想再聊点别的还是换款看看？";
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
    private final EmotionStreamingService emotionStreamingService;
    private final TTSService ttsService;

    private final boolean perspectiveEnabled;
    private final boolean orderEnabled;
    private final Duration sentenceAggregateTimeout;

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
                               EmotionStreamingService emotionStreamingService,
                               TTSService ttsService,
                               MeterRegistry meterRegistry,
                               @Value("${voice-shopping.perspective.enabled:false}") boolean perspectiveEnabled,
                               @Value("${voice-shopping.order.enabled:true}") boolean orderEnabled,
                               @Value("${voice-shopping.streaming.sentence-aggregate-timeout-ms:50}") long sentenceAggregateTimeoutMs) {
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
        this.emotionStreamingService = emotionStreamingService;
        this.ttsService = ttsService;
        this.perspectiveEnabled = perspectiveEnabled;
        this.orderEnabled = orderEnabled;
        this.sentenceAggregateTimeout = Duration.ofMillis(sentenceAggregateTimeoutMs);

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
        AgentTraceLogger.startTrace(sessionId);
        com.voiceshopping.common.cost.CostMetricsLogger.putContext(sessionId, userId);
        long t0 = System.currentTimeMillis();
        AgentTraceLogger.enter("HANDLE",
                String.format("sessionId=%s, userId=%s, utterance=%s", sessionId, userId, utterance));

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
            AgentTraceLogger.exit("HANDLE", System.currentTimeMillis() - t0,
                    "finalIntent=" + finalIntent);
            AgentTraceLogger.endTrace();
            com.voiceshopping.common.cost.CostMetricsLogger.clearContext();
        }
    }

    /**
     * 流式处理一轮对话，返回 Flux&lt;StreamChunk&gt;。
     * <p>
     * 前置逻辑（session 查找 → event 发布 → state 加载 → phase 短路 → intent 分类
     * → intent 矫正 → dispatch）与 handle() 相同，但 dispatch 后的推荐 + 情感应答
     * 改为流式：产品卡片先行 → 文字流经 SentenceAggregator 聚合 → 逐句过合规 →
     * TTS 合成音频。doFinally 中写记忆 + 持久化状态。
     *
     * @throws NotFoundException 当 sessionId 不匹配已有 session
     */
    public Flux<StreamChunk> streamHandle(String sessionId, Long userId, String utterance) {
        Timer.Sample sample = Timer.start();
        IntentEnum finalIntent = IntentEnum.OUT_OF_SCOPE;
        final String traceId = AgentTraceLogger.startTrace(sessionId);
        com.voiceshopping.common.cost.CostMetricsLogger.putContext(sessionId, userId);
        final Long traceUserId = userId;
        final long t0 = System.currentTimeMillis();
        AgentTraceLogger.enter("STREAM_HANDLE",
                String.format("sessionId=%s, userId=%s, utterance=%s", sessionId, userId, utterance));

        // === 前置逻辑（同步执行，同 handle()） ===
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("会话不存在: " + sessionId));

        voiceEventPublisher.publish(new UserSpokenEvent(
                sessionId, userId, utterance, System.currentTimeMillis()));

        SessionState state = sessionStateService.load(sessionId)
                .orElseGet(() -> initialState(sessionId, session.getMerchantId()));

        // Phase 短路：订单确认阶段走同步 handle()，结果转为 Flux
        if (orderEnabled && SessionPhase.ORDER_CONFIRM.equals(state.getPhase())) {
            BranchOutcome shortCircuit = handleOrderConfirm(sessionId, userId, state, utterance);
            if (shortCircuit != null) {
                finalIntent = IntentEnum.ORDER_CONFIRM;
                EmotionResult result = finalizeTurn(sessionId, userId, utterance, state,
                        finalIntent, state.getSlots(), shortCircuit);
                sample.stop(timersByIntent.get(finalIntent));
                AgentTraceLogger.exit("STREAM_HANDLE", System.currentTimeMillis() - t0,
                        "ORDER_CONFIRM short-circuit");
                AgentTraceLogger.endTrace();
                com.voiceshopping.common.cost.CostMetricsLogger.clearContext();
                return singleTurnFlux(result, shortCircuit.newRecItems());
            }
            state.setPhase(SessionPhase.RECOMMEND);
        }

        IntentResult intent = intentService.classify(sessionId, utterance);
        RevisedIntent revised = reviseIntent(intent, state);
        finalIntent = revised.intent();
        AgentTraceLogger.event("STREAM_HANDLE",
                "intent classified=" + intent.intent() + ", revised=" + finalIntent);

        // 关键：streaming=true 让推荐分支跳过同步 emotionService.wrap，
        // EmotionAgent 的生成由下面 buildStreamingFlux 里的 EmotionStreamingService 接管。
        BranchOutcome outcome = dispatch(sessionId, userId, utterance, state, revised, true);
        AgentTraceLogger.event("STREAM_HANDLE",
                String.format("dispatch done: hasRecItems=%s, replyPreview=%s",
                        outcome.newRecItems() != null && !outcome.newRecItems().isEmpty(),
                        outcome.reply() != null && outcome.reply().speechText() != null
                                ? outcome.reply().speechText().substring(0, Math.min(50, outcome.reply().speechText().length()))
                                : "null"));

        // === 流式输出（从 dispatch 结果构建 Flux） ===
        // traceId 透传给异步链路，doFinally 里负责 endTrace
        IntentEnum finalIntentRef = finalIntent;
        return buildStreamingFlux(sessionId, userId, utterance, state, finalIntentRef,
                revised.mergedSlots(), outcome, sample, traceId, t0);
    }

    /**
     * 将单次同步 EmotionResult 转为 Flux<StreamChunk>（用于非推荐分支：订单确认短路）。
     * 发出顺序：PRODUCTS → TEXT → AUDIO，TEXT 先于 AUDIO 到达前端，字幕同步显示。
     */
    private Flux<StreamChunk> singleTurnFlux(EmotionResult result, List<RecommendedItem> items) {
        Flux<StreamChunk> productsFlux = (items != null && !items.isEmpty())
                ? Flux.just(StreamChunk.products(items))
                : Flux.empty();

        String speechText = result != null ? result.speechText() : null;
        Flux<StreamChunk> textFlux = (speechText != null && !speechText.isBlank())
                ? Flux.just(StreamChunk.text(speechText))
                : Flux.empty();

        Flux<StreamChunk> audioFlux = flowableToFlux(ttsService.synthesize(speechText))
                .map(pcm -> StreamChunk.audio(java.nio.ByteBuffer.wrap(pcm)));

        return Flux.concat(productsFlux, textFlux, audioFlux);
    }

    /**
     * 构建 dispatch 后的流式 Flux。
     * <p>
     * 关键分支：
     * <ul>
     *   <li>推荐分支且有 items → 走 EmotionStreamingService 流式 LLM 包装</li>
     *   <li>其他所有分支（CLARIFY-ASK / 推荐空 / CHITCHAT / OUT_OF_SCOPE / ORDER_CONFIRM）
     *       → 直接用 outcome.reply().speechText() 走 TTS，不重复调 LLM</li>
     * </ul>
     * doFinally 中执行记忆写入和状态持久化。
     */
    private Flux<StreamChunk> buildStreamingFlux(String sessionId, Long userId, String utterance,
                                                 SessionState state, IntentEnum finalIntent,
                                                 Map<String, Object> mergedSlots,
                                                 BranchOutcome outcome, Timer.Sample sample,
                                                 String traceId, long traceStart) {
        AgentTraceLogger.event("STREAM_FLUX",
                "useStreamingLlm decision begins: hasItems="
                        + (outcome.newRecItems() != null && !outcome.newRecItems().isEmpty()));
        // 1. 产品帧先行
        Flux<StreamChunk> productsFlux = (outcome.newRecItems() != null && !outcome.newRecItems().isEmpty())
                ? Flux.just(StreamChunk.products(outcome.newRecItems()))
                : Flux.empty();

        // 2. 判断走流式 LLM 还是直接 TTS：
        //    只有"推荐成功且有商品"才需要流式 LLM 生成口语化文本；
        //    其他所有分支（澄清问句/空推荐兜底/闲聊/越界/订单确认）
        //    都已经在 dispatch 时把成品文本写在 outcome.reply().speechText() 里，
        //    直接拿来 TTS 即可，绝不能再调 EmotionStreamingService 把它丢掉。
        boolean useStreamingLlm = outcome.newRecItems() != null && !outcome.newRecItems().isEmpty();

        StringBuilder fullTextCollector = new StringBuilder();
        Flux<StreamChunk> textAudioFlux;

        if (useStreamingLlm) {
            // === 流式 LLM 分支：推荐分支 ===
            String userNeeds = slotsToUserNeeds(mergedSlots);
            Flux<String> charFlux = emotionStreamingService.streamWrap(
                    sessionId, utterance, userNeeds,
                    new RecommendResult(outcome.newRecItems(), "professional"));
            Flux<String> sentenceFlux = SentenceAggregator.aggregate(charFlux, sentenceAggregateTimeout);
            textAudioFlux = buildTextAudioBridge(sessionId, userId, sentenceFlux, fullTextCollector);
        } else {
            // === 直接 TTS 分支：澄清/闲聊/越界/订单/空推荐 ===
            String prebuiltReply = outcome.reply() != null ? outcome.reply().speechText() : null;
            if (prebuiltReply == null || prebuiltReply.isBlank()) {
                prebuiltReply = EmotionService.fallback(RecommendResult.EMPTY);
            }
            String safeReply = complianceChecker.ensureCompliant(
                    sessionId, userId, new EmotionResult(prebuiltReply, List.of())).speechText();
            fullTextCollector.append(safeReply);
            // 把成品文本拆成单元素流喂给同一套 TEXT+AUDIO 桥接
            Flux<String> singleSentenceFlux = Flux.just(safeReply);
            // 该分支文本已合规，桥接里再过一次合规是幂等的，沿用同一通道避免特化代码
            textAudioFlux = buildTextAudioBridge(sessionId, userId, singleSentenceFlux, new StringBuilder());
        }

        // 5. doFinally：写记忆 + 持久化状态
        Flux<StreamChunk> withPostProcessing = Flux.concat(productsFlux, textAudioFlux)
                .doFinally(signal -> {
                    // Reactor 切线程后 MDC 丢失，此处恢复 traceId 与 cost context
                    AgentTraceLogger.resumeTrace(traceId);
                    com.voiceshopping.common.cost.CostMetricsLogger.putContext(sessionId, userId);
                    AgentTraceLogger.event("STREAM_FLUX",
                            "doFinally signal=" + signal + ", fullTextLen=" + fullTextCollector.length());
                    try {
                        String fullText = fullTextCollector.toString().trim();
                        if (fullText.isBlank()) {
                            fullText = EmotionService.fallback(
                                    new RecommendResult(
                                            outcome.newRecItems() != null ? outcome.newRecItems() : List.of(),
                                            "empty"));
                        }
                        EmotionResult safeReply = complianceChecker.ensureCompliant(
                                sessionId, userId, new EmotionResult(fullText, outcome.newRecItems()));

                        // 写 ASSISTANT turn
                        Instant now = Instant.now();
                        shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                                "ASSISTANT", safeReply.speechText(), finalIntent.name(), now));

                        // 写 TURN summary
                        String summary = turnSummarizer.summarize(utterance, finalIntent, safeReply.speechText());
                        shortTermMemory.append(sessionId, new ShortTermMemory.Turn(
                                "TURN", summary, finalIntent.name(), now));

                        // 持久化 session_state
                        persistState(state, finalIntent, mergedSlots, outcome);
                    } catch (Exception e) {
                        log.error("streamHandle doFinally failed for session={}: {}", sessionId, e.getMessage(), e);
                    } finally {
                        sample.stop(timersByIntent.get(finalIntent));
                        AgentTraceLogger.exit("STREAM_HANDLE",
                                System.currentTimeMillis() - traceStart,
                                "finalIntent=" + finalIntent);
                        AgentTraceLogger.endTrace();
                        com.voiceshopping.common.cost.CostMetricsLogger.clearContext();
                    }
                });

        return withPostProcessing;
    }

    /**
     * 句子级 Flux<String> → 单会话 TTS → Flux<StreamChunk>（TEXT + AUDIO 交替）。
     * <p>
     * 单订阅 + 单 TTS 会话设计：
     * <ul>
     *   <li>每个句子先过合规 → emit TEXT 帧 → 喂给唯一的 TTS UnicastSubject</li>
     *   <li>TEXT 永远先于对应 AUDIO（onNext 顺序保证）</li>
     *   <li>句子流 complete → TTS 输入 complete → TTS 会话 complete → sink complete</li>
     * </ul>
     *
     * @param sentenceFlux         上游句子流（已是句子粒度，无需再聚合）
     * @param fullTextCollector    收集完整回复文本，doFinally 用来写记忆
     */
    private Flux<StreamChunk> buildTextAudioBridge(String sessionId, Long userId,
                                                   Flux<String> sentenceFlux,
                                                   StringBuilder fullTextCollector) {
        return Flux.create(sink -> {
            io.reactivex.subjects.UnicastSubject<String> ttsInput = io.reactivex.subjects.UnicastSubject.create();

            io.reactivex.disposables.Disposable audioSub = ttsService.streamSynthesize(
                            ttsInput.toFlowable(io.reactivex.BackpressureStrategy.BUFFER))
                    .subscribe(
                            pcm -> sink.next(StreamChunk.audio(java.nio.ByteBuffer.wrap(pcm))),
                            sink::error,
                            sink::complete
                    );

            reactor.core.Disposable textSub = sentenceFlux.subscribe(
                    sentence -> {
                        String safe = complianceChecker.ensureCompliant(
                                sessionId, userId, new EmotionResult(sentence, List.of())).speechText();
                        sink.next(StreamChunk.text(safe));
                        fullTextCollector.append(safe);
                        ttsInput.onNext(safe);
                    },
                    e -> {
                        ttsInput.onError(e);
                        sink.error(e);
                    },
                    ttsInput::onComplete
            );

            sink.onDispose(() -> {
                textSub.dispose();
                audioSub.dispose();
            });
        });
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
        return dispatch(sessionId, userId, utterance, state, revised, false);
    }

    /**
     * 带 streaming 标志的 dispatch：流式模式下，推荐分支跳过 emotionService.wrap 同步调用，
     * 把 EmotionAgent 的生成交给上层 streamHandle 走 EmotionStreamingService 流式处理，
     * 避免双调用（既同步又流式）造成的限流和字幕重复。
     */
    private BranchOutcome dispatch(String sessionId, Long userId, String utterance,
                                   SessionState state, RevisedIntent revised, boolean streaming) {
        AgentTraceLogger.event("DISPATCH",
                "intent=" + revised.intent() + ", streaming=" + streaming);
        return switch (revised.intent()) {
            case PRODUCT_RECOMMENDATION -> runRecommendation(sessionId, userId, utterance, revised.mergedSlots(), streaming);
            case CLARIFY_NEEDED         -> runClarify(sessionId, userId, utterance, revised.mergedSlots(), streaming);
            case PRODUCT_COMPARE        -> runCompare(sessionId, userId, utterance, state, revised.mergedSlots(), streaming);
            case ORDER_CONFIRM          -> runOrderConfirm(sessionId, userId, state, utterance);
            case CHITCHAT               -> runChitchat(sessionId, utterance);
            case OUT_OF_SCOPE           -> runOutOfScope();
        };
    }

    // --- Branch implementations ---

    BranchOutcome runRecommendation(String sessionId, Long userId, String utterance,
                                    Map<String, Object> slots) {
        return runRecommendation(sessionId, userId, utterance, slots, false);
    }

    BranchOutcome runRecommendation(String sessionId, Long userId, String utterance,
                                    Map<String, Object> slots, boolean streaming) {
        AgentTraceLogger.enter("RUN_REC", "streaming=" + streaming + ", slots=" + slots);
        // 1. Clarify decision — ASK short-circuits.
        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        if (clarify.action() == ClarifyResult.Action.ASK) {
            AgentTraceLogger.exit("RUN_REC", 0, "ASK shortcut");
            return new BranchOutcome(
                    new EmotionResult(clarify.questionToAsk(), List.of()),
                    SessionPhase.CLARIFY,
                    clarify.questionToAsk(),
                    null);
        }

        // 2. Recommendation pipeline.
        RecommendResult rec = parallelRecommendService.recommend(sessionId, userId, utterance, slots);
        AgentTraceLogger.event("RUN_REC", "rec items=" + (rec.items() != null ? rec.items().size() : 0));

        // 3. Perspective discussion (D12) — only when enabled and we actually have items.
        String finalUtterance = utterance;
        if (perspectiveEnabled && rec.items() != null && !rec.items().isEmpty()) {
            String panel = perspectiveHubService.discuss(sessionId, utterance, rec.items());
            if (panel != null && !panel.isBlank()) {
                finalUtterance = utterance + "\n\n[多视角点评]\n" + panel;
            }
        }

        // 4. Emotion wrap.
        // 流式模式 + 有商品 → 跳过同步 wrap，让 streamHandle 走 EmotionStreamingService。
        // BranchOutcome.reply 的 speechText 仅作为流失败时的兜底文案。
        if (streaming && rec.items() != null && !rec.items().isEmpty()) {
            AgentTraceLogger.exit("RUN_REC", 0, "streaming + hasItems → skip sync wrap");
            EmotionResult placeholder = new EmotionResult(EmotionService.fallback(rec), rec.items());
            return new BranchOutcome(placeholder, SessionPhase.RECOMMEND, null, rec.items());
        }
        String userNeeds = slotsToUserNeeds(slots);

        EmotionResult reply = emotionService.wrap(sessionId, finalUtterance, userNeeds, rec);
        AgentTraceLogger.exit("RUN_REC", 0, "sync wrap done");
        return new BranchOutcome(reply, SessionPhase.RECOMMEND, null, rec.items());
    }

    BranchOutcome runClarify(String sessionId, Long userId, String utterance,
                             Map<String, Object> slots) {
        return runClarify(sessionId, userId, utterance, slots, false);
    }

    BranchOutcome runClarify(String sessionId, Long userId, String utterance,
                             Map<String, Object> slots, boolean streaming) {
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
        return runRecommendation(sessionId, userId, utterance, slots, streaming);
    }

    BranchOutcome runCompare(String sessionId, Long userId, String utterance,
                             SessionState state, Map<String, Object> slots) {
        return runCompare(sessionId, userId, utterance, state, slots, false);
    }

    BranchOutcome runCompare(String sessionId, Long userId, String utterance,
                             SessionState state, Map<String, Object> slots, boolean streaming) {
        LastRecommendationsSnapshot snap = readLastRecommendations(state);
        if (snap == null || snap.items().isEmpty()) {
            log.info("PRODUCT_COMPARE without prior recommendations — falling back to RECOMMENDATION");
            return runRecommendation(sessionId, userId, utterance, slots, streaming);
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
        // 流式模式同样跳过同步 wrap
        if (streaming && rec.items() != null && !rec.items().isEmpty()) {
            EmotionResult placeholder = new EmotionResult(EmotionService.fallback(rec), rec.items());
            return new BranchOutcome(placeholder, SessionPhase.RECOMMEND, null, rec.items());
        }
        String compareNeeds = slotsToUserNeeds(compareSlots);
        EmotionResult reply = emotionService.wrap(sessionId, utterance, compareNeeds, rec);
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
        // CHITCHAT 不调 EmotionAgent，直接从池内随机抽一句兜底文案。
        // 上版本"先调 LLM 再判断兜底"是浪费，已废弃。
        String reply = com.voiceshopping.business.agent.ChitchatReplyPool.randomReply();
        return new BranchOutcome(
                new EmotionResult(reply, List.of()),
                SessionPhase.INTENT, null, null);
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

    /**
     * 将 slots Map 转换为 userNeeds 字符串，格式：key1=val1,key2=val2,...
     * 供 EmotionAgent 的合并 prompt 引用用户需求关键词。
     */
    static String slotsToUserNeeds(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        return slots.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    /**
     * 将 RxJava2 Flowable 转为 Reactor Flux。
     * TTSService 返回 Flowable，OrchestratorService 内部统一用 Flux。
     */
    private static <T> Flux<T> flowableToFlux(io.reactivex.Flowable<T> flowable) {
        return Flux.create(sink -> {
            io.reactivex.disposables.Disposable d = flowable.subscribe(
                    sink::next,
                    sink::error,
                    sink::complete
            );
            sink.onDispose(d::dispose);
        });
    }
}
