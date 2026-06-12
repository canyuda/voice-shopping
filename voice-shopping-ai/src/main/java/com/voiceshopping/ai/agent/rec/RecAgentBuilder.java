package com.voiceshopping.ai.agent.rec;

import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Component;

/**
 * Builder for the product recommendation agent.
 * Uses qwen-turbo for ranking and re-ranking product candidates.
 */
@Component
public class RecAgentBuilder {

    public ReActAgent build() {
        // TODO: implement in subsequent version — wire model (qwen-turbo), prompt, memory, tools
        return null;
    }
}
