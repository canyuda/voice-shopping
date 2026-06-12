package com.voiceshopping.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Voice channel configuration for ASR and TTS models / voices.
 * Mapped from {@code voice-shopping.voice} in application.yml.
 */
@ConfigurationProperties(prefix = "voice-shopping.voice")
public record VoiceProperties(
        Asr asr,
        Tts tts
) {
    public VoiceProperties {
        if (asr == null) {
            asr = new Asr("paraformer-realtime-v2");
        }
        if (tts == null) {
            tts = new Tts("cosyvoice-v1", "longwan");
        }
    }

    public record Asr(String model) {
        public Asr {
            if (model == null || model.isBlank()) {
                model = "paraformer-realtime-v2";
            }
        }
    }

    public record Tts(String model, String voice) {
        public Tts {
            if (model == null || model.isBlank()) {
                model = "cosyvoice-v1";
            }
            if (voice == null || voice.isBlank()) {
                voice = "longwan";
            }
        }
    }
}
