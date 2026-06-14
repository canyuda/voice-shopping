package com.voiceshopping.business.memory;

import com.voiceshopping.common.enums.IntentEnum;
import org.springframework.stereotype.Component;

/**
 * Compresses one completed conversation turn into a single line for short-term memory.
 * <p>
 * Output template: {@code "[<INTENT>] 用户：<utterance> / 助手：<reply>"}.
 * The summary preserves the final (post-revision) intent so downstream consumers
 * can still attribute / filter by intent without reading two raw entries.
 */
@Component
public class TurnSummarizer {

    private static final String TEMPLATE = "[%s] 用户：%s / 助手：%s";

    /**
     * Produce a single-line summary of one turn.
     *
     * @param userUtterance user utterance, must be non-null (fail-fast)
     * @param intent        final intent for the turn, must be non-null (fail-fast)
     * @param agentReply    assistant speech reply (may be empty string but not null)
     * @return formatted summary line
     * @throws IllegalArgumentException if {@code userUtterance} or {@code intent} is null
     */
    public String summarize(String userUtterance, IntentEnum intent, String agentReply) {
        if (userUtterance == null) {
            throw new IllegalArgumentException("userUtterance must not be null");
        }
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        String reply = agentReply == null ? "" : agentReply;
        return String.format(TEMPLATE, intent.name(), userUtterance, reply);
    }
}
