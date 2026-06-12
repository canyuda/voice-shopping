package com.voiceshopping.ai.tts;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.voiceshopping.ai.config.VoiceProperties;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Streaming TTS service backed by DashScope SpeechSynthesizer (ttsv2, CosyVoice).
 * Uses streamingCallAsFlowable for multi-sentence streaming synthesis.
 */
@Slf4j
@Service
public class TTSService {

    @Value("${dashscope.api-key}")
    private String apiKey;

    private final VoiceProperties voiceProperties;

    public TTSService(VoiceProperties voiceProperties) {
        this.voiceProperties = voiceProperties;
    }

    /**
     * Synthesize text to PCM audio frames (24kHz 16bit mono).
     * Empty or null text returns an empty Flowable.
     *
     * @param text text to synthesize
     * @return Flowable of PCM audio frames (byte[])
     */
    public Flowable<byte[]> synthesize(String text) {
        if (text == null || text.isBlank()) {
            return Flowable.empty();
        }

        List<String> sentences = SentenceSplitter.split(text);
        if (sentences.isEmpty()) {
            return Flowable.empty();
        }

        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .model(voiceProperties.tts().model())
                .voice(voiceProperties.tts().voice())
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(apiKey)
                .build();

        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);

        Flowable<String> textStream = Flowable.fromIterable(sentences);

        try {
            return synthesizer.streamingCallAsFlowable(textStream)
                    .filter(result -> result.getAudioFrame() != null)
                    .map(result -> {
                        ByteBuffer frame = result.getAudioFrame();
                        byte[] audio = new byte[frame.remaining()];
                        frame.get(audio);
                        return audio;
                    })
                    .doOnComplete(() -> log.debug("TTS synthesis complete for text: {}",
                            text.length() > 50 ? text.substring(0, 50) + "..." : text))
                    .doOnError(e -> log.error("TTS synthesis error", e));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }
}
