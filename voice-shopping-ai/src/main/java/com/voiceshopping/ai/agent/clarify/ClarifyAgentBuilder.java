package com.voiceshopping.ai.agent.clarify;

import com.voiceshopping.ai.agent.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builder for the clarification agent.
 * Uses qwen-turbo to generate natural follow-up questions for missing slots.
 */
@Component
public class ClarifyAgentBuilder {

    private static final String PROMPT_FILE = "clarify.txt";

    private final DashScopeChatModel model;
    private final PromptLoader promptLoader;

    public ClarifyAgentBuilder(@Qualifier("lightChatModel") DashScopeChatModel model,
                               PromptLoader promptLoader) {
        this.model = model;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("clarify_agent")
                .sysPrompt(promptLoader.load(PROMPT_FILE))
                .model(model)
                .memory(new InMemoryMemory())
                .build();
    }
}
