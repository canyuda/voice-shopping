package com.voiceshopping.ai.tts;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 将字级 Flux<String> 聚合为可合成单元（句子级）。
 * <p>
 * 用 Reactor 原生 {@link Flux#bufferUntil} 实现：累积输入 chunk 直到遇到断点标点
 * 才 emit 一个完整句子，然后重置。源 Flux 完成时，最后的未满缓冲（无尾部断点的
 * 剩余文本）也会被 emit。
 * <p>
 * 为什么不用手写 Flux.create + subscribe：早期版本用 Flux.create 包裹 charFlux.subscribe
 * + 共享 StringBuilder，在 Reactor 调度/背压下 buffer 状态会错乱，导致同一段文本被
 * 重复 emit（flush 后又被旧引用追加）。bufferUntil 是 cold-safe 的纯操作符管道，
 * 没有共享可变状态，从机制上杜绝重复。
 */
public final class SentenceAggregator {

    /** 断点标点：遇到即 flush（含确定断点和候选断点） */
    private static final Set<Character> BREAKS = Set.of(
            '。', '！', '？', '!', '?',           // 确定断点
            '，', '、', '；', ','                 // 候选断点（也立即 flush）
    );

    private SentenceAggregator() {
    }

    /**
     * 将字级流聚合为句子级流。
     *
     * @param charFlux          字级文本流
     * @param candidateTimeout  保留参数（兼容签名），当前实现不使用——所有断点立即 flush
     * @return 句子级文本流
     */
    public static Flux<String> aggregate(Flux<String> charFlux, Duration candidateTimeout) {
        return charFlux
                // 累积 chunk 直到遇到断点标点，emit 一个 List<String>，然后重置缓冲
                .bufferUntil(chunk -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return false;
                    }
                    char last = chunk.charAt(chunk.length() - 1);
                    return BREAKS.contains(last);
                })
                // 拼接为一个句子字符串
                .map(SentenceAggregator::join)
                .filter(s -> s != null && !s.isBlank());
    }

    /** 把一个缓冲区的 chunk 列表拼接为单个句子字符串 */
    private static String join(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(chunks.size() * 4);
        for (String chunk : chunks) {
            if (chunk != null) {
                sb.append(chunk);
            }
        }
        return sb.toString().trim();
    }
}
