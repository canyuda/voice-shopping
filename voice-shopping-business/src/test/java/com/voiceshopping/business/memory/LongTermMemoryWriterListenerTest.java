package com.voiceshopping.business.memory;

import com.voiceshopping.business.behavior.UserPurchasedEvent;
import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.business.session.SessionStateService;
import com.voiceshopping.infrastructure.repository.UserProfileDynamicRepository;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link LongTermMemoryWriter#onPurchasedFlushLongTerm}.
 * Verifies the AFTER_COMMIT listener delegates to {@code doFlush} only when
 * the event carries a non-blank sessionId. We invoke the listener method
 * directly — the @TransactionalEventListener wiring itself is integration-
 * tested via OrderService confirm tests under @SpringBootTest in a follow-up.
 */
class LongTermMemoryWriterListenerTest {

    private static final long USER_ID = 100L;
    private static final String SESSION_ID = "sess-1";

    private ShortTermMemory shortTermMemory;
    private SessionStateService sessionStateService;
    private UserProfileDynamicRepository dynamicRepo;
    private UserProfileService profileService;
    private LongTermMemoryWriter writer;

    @BeforeEach
    void setup() {
        shortTermMemory = mock(ShortTermMemory.class);
        sessionStateService = mock(SessionStateService.class);
        dynamicRepo = mock(UserProfileDynamicRepository.class);
        profileService = mock(UserProfileService.class);
        writer = new LongTermMemoryWriter(
                shortTermMemory, sessionStateService, dynamicRepo, profileService,
                0.05, 0.03);
    }

    @Test
    void nullSessionId_skipsSilently() {
        UserPurchasedEvent event = new UserPurchasedEvent(
                this, USER_ID, "跑鞋", "Asics", new BigDecimal("479.00"), null);

        writer.onPurchasedFlushLongTerm(event);

        // No PG / Redis interactions whatsoever.
        verifyNoInteractions(sessionStateService, dynamicRepo, profileService);
    }

    @Test
    void blankSessionId_skipsSilently() {
        UserPurchasedEvent event = new UserPurchasedEvent(
                this, USER_ID, "跑鞋", "Asics", new BigDecimal("479.00"), "  ");

        writer.onPurchasedFlushLongTerm(event);

        verifyNoInteractions(sessionStateService, dynamicRepo, profileService);
    }

    @Test
    void validSessionId_triggersDoFlush() {
        // Build a minimal SessionState with category + brand slots so doFlush
        // takes the happy path.
        SessionState state = new SessionState();
        state.setId(SESSION_ID);
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        slots.put("brand", "Asics");
        state.setSlots(slots);
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));

        UserProfileDynamic dynamic = new UserProfileDynamic();
        dynamic.setUserId(USER_ID);
        dynamic.setMerchantId(0L);
        dynamic.setCategoryPrefs(new HashMap<>());
        dynamic.setBrandPrefs(new HashMap<>());
        when(dynamicRepo.findByUserId(USER_ID)).thenReturn(Optional.of(dynamic));

        UserPurchasedEvent event = new UserPurchasedEvent(
                this, USER_ID, "跑鞋", "Asics", new BigDecimal("479.00"), SESSION_ID);

        writer.onPurchasedFlushLongTerm(event);

        verify(sessionStateService, times(1)).load(SESSION_ID);
        verify(dynamicRepo, times(1)).save(any(UserProfileDynamic.class));
        verify(profileService, times(1)).evictCache(USER_ID);
    }

    @Test
    void doFlushException_isSwallowedNotPropagated() {
        // Force an exception inside doFlush — listener must not propagate.
        when(sessionStateService.load(SESSION_ID))
                .thenThrow(new RuntimeException("PG down"));

        UserPurchasedEvent event = new UserPurchasedEvent(
                this, USER_ID, "跑鞋", "Asics", new BigDecimal("479.00"), SESSION_ID);

        // Should not throw — async exceptions are recorded, not raised.
        writer.onPurchasedFlushLongTerm(event);
        verify(dynamicRepo, never()).save(any());
    }
}
