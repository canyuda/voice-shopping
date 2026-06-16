package com.voiceshopping.business.agent;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChitchatReplyPoolTest {

    @Test
    void poolHasExactly10Replies() {
        List<String> replies = ChitchatReplyPool.repliesSnapshot();
        assertThat(replies).hasSize(10);
    }

    @Test
    void everyReplyIsNonBlankAndShortEnough() {
        for (String reply : ChitchatReplyPool.repliesSnapshot()) {
            assertThat(reply).isNotBlank();
            assertThat(reply.length()).isLessThanOrEqualTo(30);
        }
    }

    @Test
    void everyReplyGuidesBackToShopping() {
        // 文案应包含购物相关关键词
        for (String reply : ChitchatReplyPool.repliesSnapshot()) {
            boolean hasShoppingKeyword = reply.contains("购物")
                    || reply.contains("商品")
                    || reply.contains("挑")
                    || reply.contains("买")
                    || reply.contains("想看")
                    || reply.contains("想找");
            assertThat(hasShoppingKeyword)
                    .as("Reply '%s' should contain a shopping keyword", reply)
                    .isTrue();
        }
    }

    @Test
    void randomReplyAlwaysReturnsPoolMember() {
        Set<String> pool = new HashSet<>(ChitchatReplyPool.repliesSnapshot());
        for (int i = 0; i < 50; i++) {
            String reply = ChitchatReplyPool.randomReply();
            assertThat(pool).contains(reply);
        }
    }

    @Test
    void randomReplyShouldExhibitDiversity() {
        // 100 次随机至少返回 5 种不同文案（统计期望，几乎不可能全部相同）
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(ChitchatReplyPool.randomReply());
        }
        assertThat(seen.size()).isGreaterThanOrEqualTo(5);
    }
}
