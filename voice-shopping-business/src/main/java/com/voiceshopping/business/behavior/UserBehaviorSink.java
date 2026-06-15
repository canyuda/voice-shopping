package com.voiceshopping.business.behavior;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.infrastructure.repository.UserProfileDynamicRepository;
import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumes user behavior events and writes back to the dynamic profile.
 * <p>
 * Updates category_prefs, brand_prefs, recent_behavior, and purchase stats.
 * Uses Spring Events (synchronous, in-process) as the minimal implementation.
 */
@Component
public class UserBehaviorSink {

    private static final Logger log = LoggerFactory.getLogger(UserBehaviorSink.class);
    private static final int MAX_RECENT_BEHAVIOR = 50;

    private final UserProfileDynamicRepository dynamicRepo;
    private final UserProfileService profileService;
    private final double viewWeight;
    private final double purchaseWeight;

    public UserBehaviorSink(
            UserProfileDynamicRepository dynamicRepo,
            UserProfileService profileService,
            @Value("${voice-shopping.behavior.view-weight:0.05}") double viewWeight,
            @Value("${voice-shopping.behavior.purchase-weight:0.15}") double purchaseWeight) {
        this.dynamicRepo = dynamicRepo;
        this.profileService = profileService;
        this.viewWeight = viewWeight;
        this.purchaseWeight = purchaseWeight;
        log.info("UserBehaviorSink initialized: viewWeight={}, purchaseWeight={}", viewWeight, purchaseWeight);
    }

    @Async
    @EventListener
    public void onViewed(UserViewedEvent event) {
        log.debug("Processing view event: userId={}, category={}, brand={}",
                event.getUserId(), event.getCategory(), event.getBrand());

        UserProfileDynamic dynamic = getOrCreateDynamic(event.getUserId());
        incrementPref(dynamic.getCategoryPrefs(), event.getCategory(), viewWeight);
        incrementPref(dynamic.getBrandPrefs(), event.getBrand(), viewWeight);
        appendBehavior(dynamic, "view", event.getCategory(), event.getBrand(), null);

        dynamicRepo.save(dynamic);
        profileService.evictCache(event.getUserId());
        log.debug("View event processed for userId={}", event.getUserId());
    }
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPurchased(UserPurchasedEvent event) {
        log.debug("Processing purchase event: userId={}, category={}, brand={}, amount={}",
                event.getUserId(), event.getCategory(), event.getBrand(), event.getAmount());

        UserProfileDynamic dynamic = getOrCreateDynamic(event.getUserId());
        incrementPref(dynamic.getCategoryPrefs(), event.getCategory(), purchaseWeight);
        incrementPref(dynamic.getBrandPrefs(), event.getBrand(), purchaseWeight);

        // Update purchase stats
        int newCount = dynamic.getPurchaseCount() + 1;
        dynamic.setPurchaseCount(newCount);
        dynamic.setLastPurchaseAt(Instant.now());
        dynamic.setAvgOrderAmount(recalcAvg(dynamic.getAvgOrderAmount(),
                dynamic.getPurchaseCount() - 1, event.getAmount()));

        appendBehavior(dynamic, "purchase", event.getCategory(), event.getBrand(), event.getAmount());

        dynamicRepo.save(dynamic);
        profileService.evictCache(event.getUserId());
        log.debug("Purchase event processed for userId={}", event.getUserId());
    }

    private UserProfileDynamic getOrCreateDynamic(long userId) {
        return dynamicRepo.findByUserId(userId).orElseGet(() -> {
            UserProfileDynamic d = new UserProfileDynamic();
            d.setUserId(userId);
            d.setMerchantId(0L); // default, will be set by caller context
            d.setCategoryPrefs(new HashMap<>());
            d.setBrandPrefs(new HashMap<>());
            d.setRecentBehavior(new ArrayList<>());
            d.setPurchaseCount(0);
            d.setCreatedAt(Instant.now());
            d.setUpdatedAt(Instant.now());
            return d;
        });
    }

    private void incrementPref(Map<String, Double> prefs, String key, double weight) {
        if (key == null || key.isBlank()) {
            return;
        }
        prefs.merge(key, weight, Double::sum);
    }

    private void appendBehavior(UserProfileDynamic dynamic, String action,
                                String category, String brand, BigDecimal amount) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("action", action);
        entry.put("category", category);
        entry.put("brand", brand);
        entry.put("ts", Instant.now().toString());
        if (amount != null) {
            entry.put("amount", amount);
        }

        var behavior = dynamic.getRecentBehavior();
        behavior.add(entry);

        // Trim to max size
        if (behavior.size() > MAX_RECENT_BEHAVIOR) {
            dynamic.setRecentBehavior(new ArrayList<>(
                    behavior.subList(behavior.size() - MAX_RECENT_BEHAVIOR, behavior.size())));
        }
    }

    private BigDecimal recalcAvg(BigDecimal currentAvg, int prevCount, BigDecimal newAmount) {
        if (prevCount == 0) {
            return newAmount;
        }
        // newAvg = (oldAvg * oldCount + newAmount) / newCount
        BigDecimal total = currentAvg.multiply(BigDecimal.valueOf(prevCount)).add(newAmount);
        return total.divide(BigDecimal.valueOf(prevCount + 1), 2, RoundingMode.HALF_UP);
    }
}
