package com.voiceshopping.ai.model;

import io.agentscope.core.model.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentScopeConfig {

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.model.main:qwen-max}")
    private String mainModelName;

    @Value("${dashscope.model.light:qwen-turbo}")
    private String lightModelName;

    @Bean("mainChatModel")
    public DashScopeChatModel mainChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(mainModelName)
                .build();
    }

    @Bean("lightChatModel")
    public DashScopeChatModel lightChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(lightModelName)
                .build();
    }
}
