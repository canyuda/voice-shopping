package com.voiceshopping.business.memory;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.business.session.SessionStateService;
import com.voiceshopping.infrastructure.repository.UserProfileDynamicRepository;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-session long-term memory writeback. When a session ends, mentioned categories
 * and brands are accumulated into {@code user_profile_dynamic} so the next session's
 * recommendations carry forward this signal.
 * <p>
 * Mention weights are intentionally lower than purchase weights — talking about a
 * brand is a much weaker signal than buying it.
 * <p>
 * <b>Idempotency via ShortTermMemory gate.</b> The first action of {@link #doFlush}
 * is to read the most recent {@value #SHORT_TERM_GATE_LIMIT} turns from
 * {@link ShortTermMemory}. An empty list short-circuits the entire writeback —
 * after a successful flush we explicitly {@code clear} the session's short-term
 * memory, so any subsequent re-trigger (multiple of WebSocket close / order confirm
 * / TTL expire) finds the memory empty and quietly returns. No SETNX, no flush
 * markers — the conversation log is the lock.
 * <p>
 * Triggering call sites: {@code MemoryDebugController} (manual), {@code SessionExpireListener}
 * (Redis TTL expire). WebSocket close and order confirm currently delegate to the
 * cache-eviction-only path and rely on TTL for actual flush.
 */
@Component
public class LongTermMemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryWriter.class);

    /**
     * Window size used as the idempotency gate. Reading 50 turns is plenty to
     * distinguish "active session" from "already-flushed-and-cleared" without
     * paying for a longer Redis LRANGE.
     */
    static final int SHORT_TERM_GATE_LIMIT = 50;

    private final ShortTermMemory shortTermMemory;
    private final SessionStateService sessionStateService;
    private final UserProfileDynamicRepository dynamicRepo;
    private final UserProfileService profileService;
    private final double categoryMentionWeight;
    private final double brandMentionWeight;

    public LongTermMemoryWriter(
            ShortTermMemory shortTermMemory,
            SessionStateService sessionStateService,
            UserProfileDynamicRepository dynamicRepo,
            UserProfileService profileService,
            @Value("${voice-shopping.memory.long-term.category-mention-weight:0.05}") double categoryMentionWeight,
            @Value("${voice-shopping.memory.long-term.brand-mention-weight:0.03}") double brandMentionWeight) {
        this.shortTermMemory = shortTermMemory;
        this.sessionStateService = sessionStateService;
        this.dynamicRepo = dynamicRepo;
        this.profileService = profileService;
        this.categoryMentionWeight = categoryMentionWeight;
        this.brandMentionWeight = brandMentionWeight;
        log.info("LongTermMemoryWriter initialized: categoryMentionWeight={}, brandMentionWeight={}",
                categoryMentionWeight, brandMentionWeight);
    }

    /**
     * Flush mentioned categories/brands from session state to the user's dynamic profile.
     * Runs asynchronously — exceptions are logged at ERROR but never propagate to the caller.
     *
     * @param sessionId session whose slots are read
     * @param userId    profile owner to update (must not be null — fail-fast)
     */
    @Async
    public void flushOnSessionEnd(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        try {
            doFlush(sessionId, userId);
        } catch (Exception e) {
            // @Async swallows exceptions silently otherwise — record full context.
            log.error("flushOnSessionEnd failed: sessionId={}, userId={}", sessionId, userId, e);
        }
    }

    private void doFlush(String sessionId, Long userId) {
        // Idempotency gate (DISABLED in this iteration — caller controls clearing).
        // Re-enable once at least one trigger path takes responsibility for calling
        // shortTermMemory.clear(sessionId) AFTER a successful flush. Until then,
        // multi-source triggers (WS close + TTL expire) WILL double-count the same
        // session into user_profile_dynamic. See docs/short-term-memory-archive.md.
        //
        // if (shortTermMemory.recent(sessionId, SHORT_TERM_GATE_LIMIT).isEmpty()) {
        //     log.info("flushOnSessionEnd: empty ShortTermMemory for sessionId={}, treated as already-flushed",
        //             sessionId);
        //     return;
        // }

        Optional<SessionState> stateOpt = sessionStateService.load(sessionId);
        if (stateOpt.isEmpty()) {
            log.info("flushOnSessionEnd: no session_state for sessionId={}, nothing to flush", sessionId);
            return;
        }
        Map<String, Object> slots = stateOpt.get().getSlots();
        if (slots == null || slots.isEmpty()) {
            log.info("flushOnSessionEnd: empty slots for sessionId={}, nothing to flush", sessionId);
            // Do NOT clear ShortTermMemory here — clearing the gate is reserved for
            // the success path (PG save committed). TTL will reclaim it naturally.
            return;
        }

        Set<String> categories = extractStrings(slots.get("category"));
        Set<String> brands = extractStrings(slots.get("brand"));

        if (categories.isEmpty() && brands.isEmpty()) {
            log.info("flushOnSessionEnd: no category/brand mentions for sessionId={}, nothing to flush",
                    sessionId);
            return;
        }

        UserProfileDynamic dynamic = getOrCreateDynamic(userId);
        for (String cat : categories) {
            dynamic.getCategoryPrefs().merge(cat, categoryMentionWeight, Double::sum);
        }
        for (String brand : brands) {
            dynamic.getBrandPrefs().merge(brand, brandMentionWeight, Double::sum);
        }
        updatePriceSensitivity(dynamic);
        dynamic.setUpdatedAt(Instant.now());

        dynamicRepo.save(dynamic);
        profileService.evictCache(userId);
        log.info("flushOnSessionEnd: sessionId={}, userId={}, categories={}, brands={}",
                sessionId, userId, categories, brands);
    }

    /**
     * Extract a set of non-blank strings from a slot value. Tolerates the heterogeneous
     * slot shapes the IntentAgent may produce — single string, list, or anything else.
     *
     * @param slotValue raw slot value (may be null)
     * @return immutable-style mutable {@link LinkedHashSet}; empty when value is unusable
     */
    Set<String> extractStrings(Object slotValue) {
        if (slotValue == null) {
            return Collections.emptySet();
        }
        if (slotValue instanceof String s) {
            return s.isBlank() ? Collections.emptySet() : Set.of(s);
        }
        if (slotValue instanceof Collection<?> col) {
            Set<String> out = new LinkedHashSet<>();
            for (Object o : col) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }
        log.warn("Unsupported slot value shape, ignoring: type={}, value={}",
                slotValue.getClass().getSimpleName(), slotValue);
        return Collections.emptySet();
    }

    /**
     * Find existing dynamic profile or initialize a fresh empty record.
     * Mirrors {@code UserBehaviorSink#getOrCreateDynamic} — merchantId=0 placeholder,
     * empty pref/behavior collections, zero purchase count.
     */
    private UserProfileDynamic getOrCreateDynamic(Long userId) {
        return dynamicRepo.findByUserId(userId).orElseGet(() -> {
            UserProfileDynamic d = new UserProfileDynamic();
            d.setUserId(userId);
            d.setMerchantId(0L);
            d.setCategoryPrefs(new HashMap<>());
            d.setBrandPrefs(new HashMap<>());
            d.setRecentBehavior(new ArrayList<>());
            d.setPurchaseCount(0);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return d;
        });
    }

    /**
     * Placeholder — price sensitivity update strategy is not part of this iteration.
     * The next change will derive a value from session conversation cues
     * (e.g. presence of "便宜" / "贵" markers in TURN summaries).
     */
    @SuppressWarnings("unused")
    private void updatePriceSensitivity(UserProfileDynamic dynamic) {
        // TODO: implement price-sensitivity bump in a follow-up change.
    }
}
