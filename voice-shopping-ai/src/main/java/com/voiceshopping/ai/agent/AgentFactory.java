package com.voiceshopping.ai.agent;

import com.voiceshopping.ai.agent.clarify.ClarifyAgentBuilder;
import com.voiceshopping.ai.agent.intent.IntentAgentBuilder;
import com.voiceshopping.ai.agent.perspective.PerspectiveAgentBuilder;
import com.voiceshopping.ai.agent.rec.RecAgentBuilder;
import com.voiceshopping.ai.agent.emotion.EmotionAgentBuilder;
import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session-scoped LRU cache for main-pipeline Agent instances.
 * <p>
 * The four main agents (intent, clarify, rec, emotion) are cached per session
 * so InMemoryMemory persists across turns within the same session. Perspective
 * agents are transient — created fresh on each request via {@link #newPerspectiveTeam()}.
 */
@Component
public class AgentFactory {

    /** Maximum number of cached active sessions. */
    private static final int MAX_CACHED_SESSIONS = 1000;

    private final IntentAgentBuilder intentBuilder;
    private final ClarifyAgentBuilder clarifyBuilder;
    private final RecAgentBuilder recBuilder;
    private final EmotionAgentBuilder emotionBuilder;
    private final PerspectiveAgentBuilder perspectiveBuilder;
    private final PromptLoader promptLoader;

    public AgentFactory(IntentAgentBuilder intentBuilder,
                        ClarifyAgentBuilder clarifyBuilder,
                        RecAgentBuilder recBuilder,
                        EmotionAgentBuilder emotionBuilder,
                        PerspectiveAgentBuilder perspectiveBuilder,
                        PromptLoader promptLoader) {
        this.intentBuilder = intentBuilder;
        this.clarifyBuilder = clarifyBuilder;
        this.recBuilder = recBuilder;
        this.emotionBuilder = emotionBuilder;
        this.perspectiveBuilder = perspectiveBuilder;
        this.promptLoader = promptLoader;
    }

    // LRU map with access-order; eldest entry evicted when size exceeds threshold
    private final Map<String, AgentSet> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentSet> eldest) {
                    return size() > MAX_CACHED_SESSIONS;
                }
            });

    /**
     * Holds the four main-pipeline Agent references for a single session.
     */
    private record AgentSet(
            ReActAgent intentAgent,
            ReActAgent clarifyAgent,
            ReActAgent recAgent,
            ReActAgent emotionAgent
    ) {}

    /**
     * Returns (or creates and caches) the AgentSet for the given session.
     * Thread-safe via synchronized map.
     *
     * @param sessionId the session identifier
     * @return cached or newly created AgentSet
     */
    public AgentSet get(String sessionId) {
        return cache.computeIfAbsent(sessionId, id -> new AgentSet(
                intentBuilder.build(),
                clarifyBuilder.build(),
                recBuilder.build(),
                emotionBuilder.build()
        ));
    }

    // ---- single-agent accessors for services ----

    public ReActAgent getIntentAgent(String sessionId) {
        return get(sessionId).intentAgent();
    }

    public ReActAgent getClarifyAgent(String sessionId) {
        return get(sessionId).clarifyAgent();
    }

    public ReActAgent getRecAgent(String sessionId) {
        return get(sessionId).recAgent();
    }

    public ReActAgent getEmotionAgent(String sessionId) {
        return get(sessionId).emotionAgent();
    }

    /**
     * Actively removes the AgentSet for a session, releasing InMemoryMemory
     * references held by agents. Called when a session ends.
     *
     * @param sessionId the session identifier
     */
    public void remove(String sessionId) {
        cache.remove(sessionId);
    }

    /**
     * Creates a fresh perspective team (price, pro, beginner) — never cached.
     * Each invocation produces independent instances for a one-shot side analysis.
     *
     * @return a new PerspectiveTeam with three perspective agents
     */
    public PerspectiveTeam newPerspectiveTeam() {
        return new PerspectiveTeam(
                perspectiveBuilder.build("price_advisor",
                        promptLoader.load("perspective/perspective_price.txt")),
                perspectiveBuilder.build("pro_runner",
                        promptLoader.load("perspective/perspective_pro.txt")),
                perspectiveBuilder.build("beginner_buyer",
                        promptLoader.load("perspective/perspective_beginner.txt"))
        );
    }

    /**
     * Holds three perspective Agent references for a single MsgHub side analysis.
     */
    public record PerspectiveTeam(
            ReActAgent priceAgent,
            ReActAgent proAgent,
            ReActAgent beginnerAgent
    ) {}

    // ---- package-private helpers for testing ----

    int cacheSize() {
        return cache.size();
    }

    void clear() {
        cache.clear();
    }
}
