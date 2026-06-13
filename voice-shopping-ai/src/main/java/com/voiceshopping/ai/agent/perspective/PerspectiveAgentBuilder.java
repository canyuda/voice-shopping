package com.voiceshopping.ai.agent.perspective;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builder for the perspective (side commentary) agent.
 * <p>
 * One-shot analysis from a specific lens (price advisor, pro runner, beginner buyer).
 * Not cached — created fresh per invocation via {@code AgentFactory#newPerspectiveTeam()}.
 * <p>
 * Differs from the four main-pipeline builders:
 * <ul>
 *   <li>Uses {@code multiAgentChatModel} (qwen-plus + DashScopeMultiAgentFormatter)
 *       so that {@code MsgHub} conversation history is grouped correctly.</li>
 *   <li>Does NOT hold {@code PromptLoader} — accepts a pre-loaded sysPrompt string.
 *       The caller decides which prompt file to load.</li>
 *   <li>Each {@link #build} call returns a brand new {@link InMemoryMemory} so
 *       perspective discussions never leak across hub sessions.</li>
 * </ul>
 */
@Component
public class PerspectiveAgentBuilder {

    private final DashScopeChatModel model;

    public PerspectiveAgentBuilder(@Qualifier("multiAgentChatModel") DashScopeChatModel model) {
        this.model = model;
    }

    /**
     * Builds a perspective ReActAgent with the given identity and system prompt.
     *
     * @param name      agent display name, passed to {@code ReActAgent.Builder.name()}
     * @param sysPrompt pre-loaded prompt content, passed to {@code ReActAgent.Builder.sysPrompt()}
     * @return a configured ReActAgent instance with a fresh InMemoryMemory; never null
     */
    public ReActAgent build(String name, String sysPrompt) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .memory(new InMemoryMemory())
                .build();
    }
}
