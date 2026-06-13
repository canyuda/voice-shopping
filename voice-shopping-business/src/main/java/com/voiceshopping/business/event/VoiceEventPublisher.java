package com.voiceshopping.business.event;

import com.voiceshopping.common.event.UserSpokenEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin façade over {@link ApplicationEventPublisher} for voice-related events.
 * <p>
 * Intentionally does NOT wrap or swallow listener exceptions — async listener
 * failures are routed to {@code AsyncUncaughtExceptionHandler} by Spring,
 * keeping the publish call site free of error-handling clutter.
 */
@Component
public class VoiceEventPublisher {

    private final ApplicationEventPublisher publisher;

    public VoiceEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(UserSpokenEvent event) {
        publisher.publishEvent(event);
    }
}
