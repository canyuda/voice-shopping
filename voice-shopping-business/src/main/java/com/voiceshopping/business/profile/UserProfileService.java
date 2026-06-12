package com.voiceshopping.business.profile;

import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import com.voiceshopping.infrastructure.repository.UserProfileDynamicRepository;
import com.voiceshopping.infrastructure.repository.UserProfileStaticRepository;
import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import com.voiceshopping.infrastructure.repository.entity.UserProfileStatic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Optional;

/**
 * Loads user profile from PG (static + dynamic), merges into an immutable
 * {@link UserProfileSnapshot}, and caches in Redis for 24h.
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserProfileStaticRepository staticRepo;
    private final UserProfileDynamicRepository dynamicRepo;

    public UserProfileService(UserProfileStaticRepository staticRepo,
                              UserProfileDynamicRepository dynamicRepo) {
        this.staticRepo = staticRepo;
        this.dynamicRepo = dynamicRepo;
    }

    /**
     * Load and merge user profile. Result is cached in Redis.
     * If static profile is missing, returns null (user has no profile).
     * If dynamic profile is missing, uses sensible defaults.
     */
    @Cacheable(cacheNames = "userProfile", key = "#userId", unless = "#result == null")
    public UserProfileSnapshot load(long userId) {
        Optional<UserProfileStatic> staticOpt = staticRepo.findByUserId(userId);
        if (staticOpt.isEmpty()) {
            log.debug("No static profile found for userId={}", userId);
            return null;
        }

        UserProfileStatic s = staticOpt.get();
        UserProfileDynamic d = dynamicRepo.findByUserId(userId).orElse(null);

        return merge(userId, s, d);
    }

    /**
     * Evict cached profile for the given user.
     * Called after behavior sink updates to ensure next load is fresh.
     */
    @CacheEvict(cacheNames = "userProfile", key = "#userId")
    public void evictCache(long userId) {
        log.debug("Evicted profile cache for userId={}", userId);
    }

    private UserProfileSnapshot merge(long userId, UserProfileStatic s, UserProfileDynamic d) {
        return new UserProfileSnapshot(
                userId,
                s.getGender(),
                s.getAgeRange(),
                s.getCity(),
                s.getBodyHeight(),
                s.getBodyWeight(),
                s.getShoeSize(),
                s.getSkinType(),
                s.getTechFamiliarity(),
                s.getSpendingRange(),
                d != null ? d.getCategoryPrefs() : Collections.emptyMap(),
                d != null ? d.getBrandPrefs() : Collections.emptyMap(),
                d != null ? d.getPriceSensitivity() : "MEDIUM",
                d != null ? d.getAvgOrderAmount() : null,
                d != null ? d.getPurchaseCount() : 0
        );
    }
}
