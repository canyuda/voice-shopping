package com.voiceshopping.business.memory;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.business.session.SessionStateService;
import com.voiceshopping.infrastructure.repository.UserProfileDynamicRepository;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryWriterTest {

    private static final String SESSION_ID = "sess-1";
    private static final long USER_ID = 100L;
    private static final double CATEGORY_WEIGHT = 0.05;
    private static final double BRAND_WEIGHT = 0.03;

    /** Stub turn returned by ShortTermMemory.recent — kept for ctor wiring only.
     *  The writer no longer reads STM (gate disabled in this iteration). */
    private static final ShortTermMemory.Turn STUB_TURN =
            new ShortTermMemory.Turn("TURN", "[ANY] u / a", "ANY", Instant.EPOCH);

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
                CATEGORY_WEIGHT, BRAND_WEIGHT);
        // No default stub: gate is disabled, writer does not call shortTermMemory.recent.
        // STUB_TURN retained for any future re-enablement test.
    }

    // ---- fail-fast guards ----

    @Test
    void flush_nullSessionId_throws() {
        assertThatThrownBy(() -> writer.flushOnSessionEnd(null, USER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flush_blankSessionId_throws() {
        assertThatThrownBy(() -> writer.flushOnSessionEnd("  ", USER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void flush_nullUserId_throws() {
        assertThatThrownBy(() -> writer.flushOnSessionEnd(SESSION_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- extractStrings shape tolerance ----

    @Test
    void extractStrings_singleString() {
        assertThat(writer.extractStrings("跑鞋")).containsExactly("跑鞋");
    }

    @Test
    void extractStrings_listKeepsOrderDeduped() {
        assertThat(writer.extractStrings(List.of("跑鞋", "运动袜", "跑鞋")))
                .containsExactly("跑鞋", "运动袜");
    }

    @Test
    void extractStrings_blankAndNullFiltered() {
        assertThat(writer.extractStrings("")).isEmpty();
        assertThat(writer.extractStrings("   ")).isEmpty();
        assertThat(writer.extractStrings(null)).isEmpty();
        assertThat(writer.extractStrings(List.of())).isEmpty();
    }

    @Test
    void extractStrings_unsupportedShapeReturnsEmpty() {
        assertThat(writer.extractStrings(42)).isEmpty();
        assertThat(writer.extractStrings(Map.of("k", "v"))).isEmpty();
    }

    // ---- core flush behavior ----

    @Test
    void flush_singleStringCategory_increments() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        whenSlots(slots);
        UserProfileDynamic existing = newDynamicWith(Map.of("跑鞋", 0.10), Map.of(), "MEDIUM");
        when(dynamicRepo.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        UserProfileDynamic saved = captureSaved();
        // 0.10 + 0.05 — float arithmetic, allow tiny epsilon.
        assertThat(saved.getCategoryPrefs().get("跑鞋")).isCloseTo(0.15, offset(1e-9));
        assertThat(saved.getPriceSensitivity()).isEqualTo("MEDIUM");
        verify(profileService).evictCache(USER_ID);
    }

    @Test
    void flush_listCategoryAndBrand_appliedSeparately() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", List.of("跑鞋", "运动袜"));
        slots.put("brand", "Nike");
        whenSlots(slots);
        UserProfileDynamic existing = newDynamicWith(new HashMap<>(), new HashMap<>(), "MEDIUM");
        when(dynamicRepo.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        UserProfileDynamic saved = captureSaved();
        assertThat(saved.getCategoryPrefs().get("跑鞋")).isCloseTo(CATEGORY_WEIGHT, offset(1e-9));
        assertThat(saved.getCategoryPrefs().get("运动袜")).isCloseTo(CATEGORY_WEIGHT, offset(1e-9));
        assertThat(saved.getBrandPrefs().get("Nike")).isCloseTo(BRAND_WEIGHT, offset(1e-9));
    }

    @Test
    void flush_emptySlots_skipsWrite() {
        whenSlots(new HashMap<>());

        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        verify(dynamicRepo, never()).save(any());
        verify(profileService, never()).evictCache(eq(USER_ID));
    }

    @Test
    void flush_noSessionState_skipsWrite() {
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.empty());

        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        verify(dynamicRepo, never()).save(any());
    }

    @Test
    void flush_dynamicMissing_initializesNewRow() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        whenSlots(slots);
        when(dynamicRepo.findByUserId(USER_ID)).thenReturn(Optional.empty());

        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        UserProfileDynamic saved = captureSaved();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getMerchantId()).isEqualTo(0L);
        assertThat(saved.getPurchaseCount()).isZero();
        assertThat(saved.getCategoryPrefs().get("跑鞋")).isCloseTo(CATEGORY_WEIGHT, offset(1e-9));
        assertThat(saved.getBrandPrefs()).isEmpty();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void flush_priceSensitivityUntouched() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        whenSlots(slots);
        UserProfileDynamic existing = newDynamicWith(new HashMap<>(), new HashMap<>(), "HIGH");
        when(dynamicRepo.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        UserProfileDynamic saved = captureSaved();
        assertThat(saved.getPriceSensitivity()).isEqualTo("HIGH");
    }

    @Test
    void flush_pgWriteFailure_swallowed() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("category", "跑鞋");
        whenSlots(slots);
        when(dynamicRepo.findByUserId(USER_ID))
                .thenReturn(Optional.of(newDynamicWith(new HashMap<>(), new HashMap<>(), "MEDIUM")));
        doThrow(new RuntimeException("db down")).when(dynamicRepo).save(any());

        // No exception propagates, profile cache not evicted.
        writer.flushOnSessionEnd(SESSION_ID, USER_ID);

        verify(profileService, never()).evictCache(eq(USER_ID));
    }

    // ---- helpers ----

    private void whenSlots(Map<String, Object> slots) {
        SessionState state = new SessionState();
        state.setId(SESSION_ID);
        state.setSlots(slots);
        when(sessionStateService.load(SESSION_ID)).thenReturn(Optional.of(state));
    }

    private static UserProfileDynamic newDynamicWith(
            Map<String, Double> categoryPrefs,
            Map<String, Double> brandPrefs,
            String priceSensitivity) {
        UserProfileDynamic d = new UserProfileDynamic();
        d.setUserId(USER_ID);
        d.setMerchantId(0L);
        d.setCategoryPrefs(new HashMap<>(categoryPrefs));
        d.setBrandPrefs(new HashMap<>(brandPrefs));
        d.setPriceSensitivity(priceSensitivity);
        d.setPurchaseCount(0);
        return d;
    }

    private UserProfileDynamic captureSaved() {
        ArgumentCaptor<UserProfileDynamic> captor = ArgumentCaptor.forClass(UserProfileDynamic.class);
        verify(dynamicRepo).save(captor.capture());
        return captor.getValue();
    }
}
