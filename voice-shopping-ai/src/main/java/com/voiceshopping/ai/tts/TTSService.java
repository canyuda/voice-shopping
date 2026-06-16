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
        log.info("[TTS.synthesize] ENTER text={}, callerStack={}",
                text == null ? "null" : (text.length() > 100 ? text.substring(0, 100) + "..." : text),
                shortCallerStack(5));
        if (text == null || text.isBlank()) {
            return Flowable.empty();
        }

        List<String> sentences = SentenceSplitter.split(text);
        if (sentences.isEmpty()) {
            return Flowable.empty();
        }
        log.info("[TTS.synthesize] split into {} sentences: {}", sentences.size(), sentences);

        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .model(voiceProperties.tts().model())
                .voice(voiceProperties.tts().voice())
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(apiKey)
                .build();

        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);

        Flowable<String> textStream = Flowable.fromIterable(sentences)
                .doOnNext(s -> log.info("[TTS.synthesize] feeding sentence to DashScope: {}", s));

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

    /**
     * 流式合成：将外部句子流喂给单个 synthesizer 会话。
     * <p>
     * 与 {@link #synthesize(String)} 的关键区别：整个输入流只建 1 个
     * synthesizer 会话，把所有句子复用同一个连接，避免逐句调用导致
     * DashScope 限流（Throttling.RateQuota）。
     *
     * @param sentenceStream 句子输入流（Reactive Streams Publisher，可来自 Reactor Flux）
     * @return Flowable of PCM audio frames (byte[])
     */
    public Flowable<byte[]> streamSynthesize(org.reactivestreams.Publisher<String> sentenceStream) {
        log.info("[TTS.streamSynthesize] ENTER (single-session mode), callerStack={}",
                shortCallerStack(5));
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .model(voiceProperties.tts().model())
                .voice(voiceProperties.tts().voice())
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(apiKey)
                .build();

        // 单会话：所有句子喂给同一个 synthesizer
        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);

        // 在每个句子喂给 DashScope 前打日志，与 OkHttpWebSocketClient.Sending message
        // 的 continue-task 一一对应；如果一段文本被喂了两次，会立即在日志里看到。
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        Flowable<String> tracedStream = Flowable.fromPublisher(sentenceStream)
                .doOnNext(s -> log.info("[TTS.streamSynthesize] #{} feeding sentence to DashScope: {}",
                        seq.incrementAndGet(), s))
                .doOnComplete(() -> log.info("[TTS.streamSynthesize] input stream complete, total sentences fed={}",
                        seq.get()));

        try {
            return synthesizer.streamingCallAsFlowable(tracedStream)
                    .filter(result -> result.getAudioFrame() != null)
                    .map(result -> {
                        ByteBuffer frame = result.getAudioFrame();
                        byte[] audio = new byte[frame.remaining()];
                        frame.get(audio);
                        return audio;
                    })
                    .doOnComplete(() -> log.debug("TTS stream synthesis complete"))
                    .doOnError(e -> log.error("TTS stream synthesis error", e));
        } catch (Exception e) {
            return Flowable.error(e);
        }
    }

    /** 取调用栈前 N 个非本类的帧，定位是谁调用了 TTS（多帧帮助看完整路径） */
    private static String shortCallerStack(int depth) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (cls.startsWith("java.lang.Thread") || cls.equals(TTSService.class.getName())) {
                continue;
            }
            if (count > 0) sb.append(" ← ");
            sb.append(cls.substring(cls.lastIndexOf('.') + 1))
              .append(".").append(el.getMethodName())
              .append(":").append(el.getLineNumber());
            if (++count >= depth) break;
        }
        return sb.toString();
    }
}
