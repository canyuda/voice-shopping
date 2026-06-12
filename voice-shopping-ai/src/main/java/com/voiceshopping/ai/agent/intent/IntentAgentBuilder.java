package com.voiceshopping.ai.agent.intent;

import com.voiceshopping.ai.agent.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builder for the intent understanding agent.
 * Uses qwen-turbo for lightweight intent classification.
 */
@Component
public class IntentAgentBuilder {

    private static final String PROMPT_FILE = "intent.txt";

    private final DashScopeChatModel model;
    private final PromptLoader promptLoader;

    public IntentAgentBuilder(@Qualifier("lightChatModel") DashScopeChatModel model,
                              PromptLoader promptLoader) {
        this.model = model;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("IntentAgent")
                .sysPrompt(promptLoader.load(PROMPT_FILE))
                .model(model)
                .memory(new InMemoryMemory())
                .build();
    }
}
