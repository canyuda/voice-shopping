package com.voiceshopping.ai.agent.emotion;

import com.voiceshopping.ai.agent.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builder for the emotion response agent.
 * Uses qwen-max (mainChatModel) for high-quality natural spoken responses,
 * since this agent's output is the final text fed to TTS.
 */
@Component
public class EmotionAgentBuilder {

    private static final String PROMPT_FILE = "emotion.txt";

    private final DashScopeChatModel model;
    private final PromptLoader promptLoader;

    public EmotionAgentBuilder(@Qualifier("mainChatModel") DashScopeChatModel model,
                               PromptLoader promptLoader) {
        this.model = model;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("emotion_agent")
                .sysPrompt(promptLoader.load(PROMPT_FILE))
                .model(model)
                .memory(new InMemoryMemory())
                .build();
    }
}
