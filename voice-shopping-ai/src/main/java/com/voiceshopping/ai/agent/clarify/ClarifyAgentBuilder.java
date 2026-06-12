package com.voiceshopping.ai.agent.clarify;

import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Component;

/**
 * Builder for the clarification agent.
 * Rule-first with LLM (qwen-turbo) fallback for slot filling.
 */
@Component
public class ClarifyAgentBuilder {

    public ReActAgent build() {
        // TODO: implement in subsequent version — wire model (qwen-turbo), prompt, memory, tools
        return null;
    }
}
