package com.voiceshopping.ai.agent.sentiment;

import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Component;

/**
 * Builder for the sentiment response agent.
 * Uses qwen-max for natural conversational responses.
 */
@Component
public class SentimentAgentBuilder {

    public ReActAgent build() {
        // TODO: implement in subsequent version — wire model (qwen-max), prompt, memory, tools
        return null;
    }
}
