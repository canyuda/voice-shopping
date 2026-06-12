package com.voiceshopping.ai.asr;

import com.voiceshopping.ai.config.VoiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for creating ASRService instances per WebSocket session.
 * Each session gets its own ASRService with the configured API key and model.
 */
@Slf4j
@Component
public class ASRServiceFactory {

    @Value("${dashscope.api-key}")
    private String apiKey;

    private final VoiceProperties voiceProperties;

    public ASRServiceFactory(VoiceProperties voiceProperties) {
        this.voiceProperties = voiceProperties;
    }

    /**
     * Create a new ASRService instance bound to the configured API key and model.
     *
     * @return new ASRService instance
     */
    public ASRService create() {
        return new ASRService(apiKey, voiceProperties.asr().model());
    }
}
