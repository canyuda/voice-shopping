package com.voiceshopping.ai.agent.emotion;

import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Component;

/**
 * Builder for the emotion response agent.
 * Uses qwen-max for natural conversational responses.
 */
@Component
public class EmotionAgentBuilder {

    public ReActAgent build() {
        // TODO: implement in subsequent version — wire model (qwen-max), prompt, memory, tools
        return null;
    }
}
