package com.voiceshopping.ai.asr;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.voiceshopping.common.cost.CostMetricsLogger;
import io.reactivex.Flowable;
import io.reactivex.processors.PublishProcessor;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streaming ASR service backed by DashScope Recognition.
 * Lifecycle: start() → sendFrame()* → stop(). Thread-safe via AtomicBoolean guards.
 * Not a Spring singleton — create a new instance per WebSocket session via {@link ASRServiceFactory}.
 * <p>
 * 成本埋点：每帧 PCM 按 16kHz/16bit/mono 折算音频时长累加；session 结束时
 * （onComplete / onError / stop()）通过 {@link CostMetricsLogger} 输出一条 ASR 日志。
 */
@Slf4j
public class ASRService {

    /** 每个 PCM 字节对应的音频毫秒数：16000 samples/s × 2 bytes/sample = 32 bytes/ms */
    private static final double MS_PER_BYTE = 1.0 / 32.0;

    private final String apiKey;
    private final String model;
    private Recognition recognition;
    private PublishProcessor<RecognitionResult> resultProcessor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** 成本埋点：累计接收的音频字节数（折算 audioMs） */
    private final AtomicLong audioBytesAccumulated = new AtomicLong(0);
    /** 成本埋点：start() 时刻，用于计算 durationMs */
    private long sessionStartMs;
    /** 是否已埋点（session 结束信号可能多次触发，要去重） */
    private final AtomicBoolean costLogged = new AtomicBoolean(false);

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
        audioBytesAccumulated.set(0);
        costLogged.set(false);
        sessionStartMs = System.currentTimeMillis();
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
                logCostOnce();
                resultProcessor.onComplete();
                resetState();
            }

            @Override
            public void onError(Exception e) {
                log.error("ASR error", e);
                logCostOnce();
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
        // 累计音频字节，session 结束时折算为 audioMs 用于成本埋点
        audioBytesAccumulated.addAndGet(pcm.length);
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
            // stop() 触发后 onComplete 也会回调，logCostOnce 用 AtomicBoolean 去重，仅 1 条日志
            logCostOnce();
        }
    }

    /**
     * 成本埋点（去重，session 结束信号可能多次触发：onComplete + stop()）。
     */
    private void logCostOnce() {
        if (!costLogged.compareAndSet(false, true)) {
            return;
        }
        long audioMs = (long) (audioBytesAccumulated.get() * MS_PER_BYTE);
        long durationMs = System.currentTimeMillis() - sessionStartMs;
        CostMetricsLogger.logAsr(model, audioMs, durationMs);
    }

    private void resetState() {
        started.set(false);
        stopped.set(false);
    }
}
