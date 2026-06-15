package com.voiceshopping.business.behavior;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.infrastructure.repository.UserProfileDynamicRepository;
import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link UserBehaviorSink#onPurchased} is wired with the
 * {@code @TransactionalEventListener(AFTER_COMMIT)} annotation, and that
 * direct invocation still mutates {@code user_profile_dynamic} as before.
 * <p>
 * Full transactional semantics (rollback skip / commit fire) require a
 * Spring TransactionTemplate; that's covered as a manual ChatDebug check
 * (tasks.md §7) since pure-unit replication would mostly test Spring's
 * own infrastructure, not our code.
 */
class UserBehaviorSinkTransactionalTest {

    private UserProfileDynamicRepository dynamicRepo;
    private UserProfileService profileService;
    private UserBehaviorSink sink;

    @BeforeEach
    void setup() {
        dynamicRepo = mock(UserProfileDynamicRepository.class);
        profileService = mock(UserProfileService.class);
        sink = new UserBehaviorSink(dynamicRepo, profileService, 0.05, 0.15);
    }

    @Test
    void onPurchasedIsAnnotatedAsTransactionalAfterCommit() throws Exception {
        Method m = UserBehaviorSink.class.getMethod("onPurchased", UserPurchasedEvent.class);
        TransactionalEventListener annotation = m.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation)
                .as("onPurchased must use @TransactionalEventListener so order rollback skips it")
                .isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void onPurchased_directCall_updatesPreferencesAndEvictsCache() {
        // Direct invocation simulates "AFTER_COMMIT phase fired by Spring".
        // Verifies the existing PG / cache writes are still in place.
        UserProfileDynamic dynamic = new UserProfileDynamic();
        dynamic.setUserId(100L);
        dynamic.setMerchantId(0L);
        dynamic.setCategoryPrefs(new HashMap<>());
        dynamic.setBrandPrefs(new HashMap<>());
        dynamic.setRecentBehavior(new java.util.ArrayList<>());
        dynamic.setPurchaseCount(2);
        dynamic.setAvgOrderAmount(new BigDecimal("100.00"));
        when(dynamicRepo.findByUserId(100L)).thenReturn(Optional.of(dynamic));

        UserPurchasedEvent event = new UserPurchasedEvent(
                this, 100L, "跑鞋", "Asics", new BigDecimal("400.00"), "sess-1");

        sink.onPurchased(event);

        verify(dynamicRepo).save(dynamic);
        verify(profileService).evictCache(100L);

        // Hand-computed expectations — purchaseWeight=0.15 by default.
        assertThat(dynamic.getCategoryPrefs()).containsEntry("跑鞋", 0.15);
        assertThat(dynamic.getBrandPrefs()).containsEntry("Asics", 0.15);
        assertThat(dynamic.getPurchaseCount()).isEqualTo(3);
        // newAvg = (100 * 2 + 400) / 3 = 200.00
        assertThat(dynamic.getAvgOrderAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void onPurchased_legacyEventWithoutSessionId_stillUpdatesProfile() {
        // Back-office paths can publish without session context. Sink must not
        // care — it doesn't read sessionId.
        when(dynamicRepo.findByUserId(any())).thenReturn(Optional.empty());

        UserPurchasedEvent legacy = new UserPurchasedEvent(
                this, 100L, "跑鞋", "Nike", new BigDecimal("300.00"));

        sink.onPurchased(legacy);

        verify(dynamicRepo).save(any(UserProfileDynamic.class));
    }
}
