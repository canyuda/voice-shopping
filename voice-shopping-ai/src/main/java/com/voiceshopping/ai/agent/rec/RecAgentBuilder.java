package com.voiceshopping.ai.agent.rec;

import com.voiceshopping.ai.agent.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builder for the product recommendation agent.
 * Uses qwen-max (mainChatModel) for reasoning-intensive reason generation.
 */
@Component
public class RecAgentBuilder {

    private static final String PROMPT_FILE = "rec.txt";

    private final DashScopeChatModel model;
    private final PromptLoader promptLoader;

    public RecAgentBuilder(@Qualifier("mainChatModel") DashScopeChatModel model,
                           PromptLoader promptLoader) {
        this.model = model;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("recommend_agent")
                .sysPrompt(promptLoader.load(PROMPT_FILE))
                .model(model)
                .memory(new InMemoryMemory())
                .build();
    }
}
