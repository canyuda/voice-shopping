package com.voiceshopping.ai.asr;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import io.reactivex.Flowable;
import io.reactivex.processors.PublishProcessor;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming ASR service backed by DashScope Recognition.
 * Lifecycle: start() → sendFrame()* → stop(). Thread-safe via AtomicBoolean guards.
 * Not a Spring singleton — create a new instance per WebSocket session via {@link ASRServiceFactory}.
 */
@Slf4j
public class ASRService {

    private final String apiKey;
    private final String model;
    private Recognition recognition;
    private PublishProcessor<RecognitionResult> resultProcessor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public ASRService(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * Start ASR session. Constructs RecognitionParam (configurable model, pcm, 16kHz)
     * and opens a full-duplex connection via Recognition.call(param, callback).
     *
     * @return Flowable of RecognitionResult (both partial and sentence-end)
     */
    public Flowable<RecognitionResult> start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("ASR session already started");
        }
        stopped.set(false);
        resultProcessor = PublishProcessor.create();

        recognition = new Recognition();

        RecognitionParam param = RecognitionParam.builder()
                .model(model)
                .format("pcm")
                .sampleRate(16000)
                .apiKey(apiKey)
                .build();

        recognition.call(param, new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result.getSentence() != null && result.getSentence().getText() != null
                        && !result.getSentence().getText().isEmpty()) {
                    log.debug("ASR event: text={}, isSentenceEnd={}",
                            result.getSentence().getText(), result.isSentenceEnd());
                }
                resultProcessor.onNext(result);
            }

            @Override
            public void onComplete() {
                log.info("ASR session completed");
                resultProcessor.onComplete();
                resetState();
            }

            @Override
            public void onError(Exception e) {
                log.error("ASR error", e);
                resultProcessor.onError(e);
                resetState();
            }
        });

        log.info("ASR session started");
        return resultProcessor;
    }

    /**
     * Send a PCM audio frame to the ASR engine.
     *
     * @param pcm 16kHz 16bit mono PCM data
     * @throws IllegalStateException if session not started
     */
    public void sendFrame(byte[] pcm) {
        if (!started.get()) {
            throw new IllegalStateException("ASR session not started, call start() first");
        }
        if (stopped.get()) {
            log.warn("ASR session already stopped, ignoring sendFrame");
            return;
        }
        recognition.sendAudioFrame(ByteBuffer.wrap(pcm));
    }

    /**
     * Stop the ASR session and release resources.
     */
    public void stop() {
        if (started.get() && !stopped.get()) {
            stopped.set(true);
            try {
                recognition.stop();
                log.info("ASR session stopped");
            } catch (Exception e) {
                log.error("Error stopping ASR session", e);
            }
        }
    }

    private void resetState() {
        started.set(false);
        stopped.set(false);
    }
}
