package com.voiceshopping.business.agent;

import io.agentscope.core.ReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Centralized policy for the three worker agents' {@link io.agentscope.core.memory.InMemoryMemory}
 * lifecycle. Business services MUST go through {@code before*Call} hooks instead of touching
 * {@code agent.getMemory().clear()} / {@code deleteMessage(...)} directly, so memory hygiene
 * stays in one place.
 * <ul>
 *   <li>Intent — clear every turn (stateless classification).</li>
 *   <li>Clarify — clear every turn (each turn re-derives questions from current slots).</li>
 *   <li>Emotion — keep last 40 messages (~20 rounds) for mood tracking.</li>
 * </ul>
 * 历史：旧版本含 beforeRecommendCall（保留 RecAgent 8 条记忆），
 * 已随 RecAgent 一并删除。
 */
@Component
public class AgentMemoryPolicy {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryPolicy.class);

    /** Emotion agent retains ~20 user/assistant rounds for mood tracking. */
    static final int EMOTION_LIMIT = 40;

    /** Intent classification is stateless — drop everything before each call. */
    public void beforeIntentCall(ReActAgent agent) {
        agent.getMemory().clear();
        log.debug("[AgentMemoryPolicy] intent memory cleared");
    }

    /** Clarify is stateless from the model's POV — slots carry all signal. */
    public void beforeClarifyCall(ReActAgent agent) {
        agent.getMemory().clear();
        log.debug("[AgentMemoryPolicy] clarify memory cleared");
    }

    /** Trim emotion agent memory to {@link #EMOTION_LIMIT}. */
    public void beforeEmotionCall(ReActAgent agent) {
        trimToLast(agent, EMOTION_LIMIT, "emotion");
    }

    /**
     * Drop oldest messages until memory size is at most {@code limit}. Boundary-safe:
     * empty memory or size already &le; limit is a no-op (no {@code deleteMessage} call).
     *
     * @param agent target agent (its in-memory store is mutated in place)
     * @param limit retention size (must be &ge; 0)
     * @param tag   short identifier used in DEBUG logs
     */
    private void trimToLast(ReActAgent agent, int limit, String tag) {
        int size = agent.getMemory().getMessages().size();
        if (size <= limit) {
            return;
        }
        int dropped = 0;
        while (agent.getMemory().getMessages().size() > limit) {
            agent.getMemory().deleteMessage(0);
            dropped++;
        }
        log.debug("[AgentMemoryPolicy] {} memory trimmed: dropped={}, kept={}", tag, dropped, limit);
    }
}
