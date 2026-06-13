package com.voiceshopping.business.event;

import com.voiceshopping.common.event.UserSpokenEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the async event-bus contract:
 * <ul>
 *   <li>Listener with {@code Thread.sleep(500)} does NOT block publish.</li>
 *   <li>Listener throwing RuntimeException does NOT propagate back to publish.</li>
 * </ul>
 * <p>
 * NOTE: We use static counters/flags rather than instance fields on a
 * Spring-managed listener bean — when {@code @Async} is on the listener,
 * Spring proxies it (CGLIB), and field reads via the proxy bypass the
 * underlying instance state. Static state sidesteps that interaction without
 * weakening the test.
 */
@SpringBootTest(classes = {
        VoiceEventAsyncTest.TestConfig.class,
        VoiceEventPublisher.class
})
class VoiceEventAsyncTest {

    static final AtomicBoolean THROW_ON_NEXT = new AtomicBoolean(false);
    static final AtomicInteger INVOCATIONS = new AtomicInteger(0);

    @Autowired
    private VoiceEventPublisher publisher;

    @Test
    void publishReturnsImmediately_evenWhenListenerSleeps() {
        THROW_ON_NEXT.set(false);
        int before = INVOCATIONS.get();

        long t0 = System.nanoTime();
        publisher.publish(new UserSpokenEvent("s1", 1L, "买跑鞋", System.currentTimeMillis()));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // Publish thread MUST NOT have waited on the listener's sleep(500).
        assertTrue(elapsedMs < 200, "publish should return quickly, took " + elapsedMs + "ms");

        // Listener still ran in background — wait up to 2s for invocation count to advance.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && INVOCATIONS.get() == before) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        assertTrue(INVOCATIONS.get() > before, "async listener should eventually run");
    }

    @Test
    void listenerExceptionDoesNotPropagate() {
        THROW_ON_NEXT.set(true);

        // No exception propagates back to publish.
        assertDoesNotThrow(() ->
                publisher.publish(new UserSpokenEvent("s2", 1L, "x", System.currentTimeMillis())));
    }

    @EnableAsync
    @Configuration
    static class TestConfig {
        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }

    static class RecordingListener {
        @Async
        @EventListener
        public void onSpoken(UserSpokenEvent e) throws InterruptedException {
            if (THROW_ON_NEXT.getAndSet(false)) {
                throw new RuntimeException("simulated listener failure");
            }
            Thread.sleep(500);
            INVOCATIONS.incrementAndGet();
        }
    }
}
