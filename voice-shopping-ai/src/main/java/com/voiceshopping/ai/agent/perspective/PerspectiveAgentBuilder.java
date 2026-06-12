package com.voiceshopping.ai.agent.perspective;

import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Component;

/**
 * Builder for the perspective (side commentary) agent.
 * One-shot analysis from a specific lens (price, expert, beginner).
 * Not cached — created fresh per invocation via {@code AgentFactory#newPerspectiveTeam()}.
 */
@Component
public class PerspectiveAgentBuilder {

    /**
     * Builds a perspective ReActAgent with the given identity and system prompt.
     *
     * @param name      agent display name, passed to {@code ReActAgent.Builder.name()}
     * @param sysPrompt loaded prompt content, passed to {@code ReActAgent.Builder.sysPrompt()}
     * @return a configured ReActAgent instance
     */
    public ReActAgent build(String name, String sysPrompt) {
        // TODO: implement in subsequent version — wire model, memory, tools
        return null;
    }
}
