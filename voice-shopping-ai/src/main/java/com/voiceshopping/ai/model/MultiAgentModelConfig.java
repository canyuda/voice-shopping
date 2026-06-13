package com.voiceshopping.ai.model;

import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean for the multi-agent (MsgHub) DashScope chat model.
 * <p>
 * Wired with {@link DashScopeMultiAgentFormatter} so that conversation history
 * across multiple agent participants is grouped properly when sent to qwen-plus.
 * <p>
 * apiKey shares the same source as {@link AgentScopeConfig} ({@code dashscope.api-key}).
 * Model name defaults to {@code qwen-plus} and can be overridden via
 * {@code dashscope.model.multi-agent}.
 */
@Configuration
public class MultiAgentModelConfig {

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.model.multi-agent:qwen-plus}")
    private String multiAgentModelName;

    @Bean("multiAgentChatModel")
    public DashScopeChatModel multiAgentChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(multiAgentModelName)
                .formatter(new DashScopeMultiAgentFormatter())
                .build();
    }
}
