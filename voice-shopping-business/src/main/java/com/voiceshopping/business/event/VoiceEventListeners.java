package com.voiceshopping.business.event;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.common.event.UserSpokenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async listeners for {@link UserSpokenEvent}.
 * <p>
 * Both methods run on the {@code @EnableAsync} task executor (already enabled
 * on {@code VoiceShoppingApplication}). They intentionally do NOT propagate
 * exceptions back to the publisher — async listeners are by design isolated
 * from the publishing thread.
 */
@Component
public class VoiceEventListeners {

    private static final Logger log = LoggerFactory.getLogger(VoiceEventListeners.class);

    private final UserProfileService profileService;

    public VoiceEventListeners(UserProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * Best-effort profile cache warmup. Triggers {@link UserProfileService#load}
     * so that the snapshot is populated in Redis before downstream agents need
     * it. Failures are swallowed at the listener boundary — they MUST NOT
     * propagate back to the publisher.
     */
    @Async
    @EventListener
    public void onUserSpokenWarmup(UserSpokenEvent event) {
        if (event.userId() == null) {
            return;
        }
        try {
            profileService.load(event.userId());
            log.info("[WARMUP] userId={} 画像已预热", event.userId());
        } catch (Exception e) {
            log.warn("画像预热失败 userId={}", event.userId(), e);
        }
    }

    /**
     * Audit trail for every user utterance. INFO level so it lands in
     * production logs by default.
     */
    @Async
    @EventListener
    public void onUserSpokenAudit(UserSpokenEvent event) {
        log.info("AUDIT user-spoken sessionId={} userId={} text={}",
                event.sessionId(), event.userId(), event.utterance());
    }
}
