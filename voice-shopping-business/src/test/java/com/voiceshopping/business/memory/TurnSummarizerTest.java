package com.voiceshopping.business.memory;

import com.voiceshopping.common.enums.IntentEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnSummarizerTest {

    private final TurnSummarizer summarizer = new TurnSummarizer();

    @Test
    void summarize_standardFormat() {
        String result = summarizer.summarize(
                "想买跑鞋", IntentEnum.PRODUCT_RECOMMENDATION, "推荐 Nike Air Zoom");

        assertThat(result).isEqualTo("[PRODUCT_RECOMMENDATION] 用户：想买跑鞋 / 助手：推荐 Nike Air Zoom");
    }

    @Test
    void summarize_emptyAgentReplyRetainsTrailingEmpty() {
        String result = summarizer.summarize("嗯嗯", IntentEnum.CHITCHAT, "");

        assertThat(result).isEqualTo("[CHITCHAT] 用户：嗯嗯 / 助手：");
    }

    @Test
    void summarize_nullAgentReplyTreatedAsEmpty() {
        String result = summarizer.summarize("嗯嗯", IntentEnum.CHITCHAT, null);

        assertThat(result).isEqualTo("[CHITCHAT] 用户：嗯嗯 / 助手：");
    }

    @Test
    void summarize_nullUserUtteranceFailsFast() {
        assertThatThrownBy(() -> summarizer.summarize(null, IntentEnum.CHITCHAT, "ok"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summarize_nullIntentFailsFast() {
        assertThatThrownBy(() -> summarizer.summarize("hi", null, "ok"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
