package com.voiceshopping.business.agent;

import com.voiceshopping.business.memory.ShortTermMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionMoodDetectorTest {

    /** ShortTermMemory stub returning a fixed recent-turn list, ignoring args. */
    private static ShortTermMemory memoryWith(List<ShortTermMemory.Turn> turns) {
        return new ShortTermMemory(null, null, Duration.ofMinutes(30), 20) {
            @Override
            public List<Turn> recent(String sessionId, int n) {
                return turns;
            }
        };
    }

    private static ShortTermMemory.Turn assistant(String content) {
        return new ShortTermMemory.Turn("ASSISTANT", content, 1, "EmotionAgent", Instant.EPOCH);
    }

    private static ShortTermMemory.Turn user(String content) {
        return new ShortTermMemory.Turn("USER", content, 0, null, Instant.EPOCH);
    }

    private String detect(String utterance, List<ShortTermMemory.Turn> history) {
        return new SessionMoodDetector(memoryWith(history)).detect("s1", utterance);
    }

    @Nested
    @DisplayName("byRule priority chain (impatient > negative > positive)")
    class ByRule {

        @Test
        @DisplayName("impatient matched first")
        void impatient() {
            assertEquals("impatient", detect("算了吧，太慢了", List.of()));
        }

        @Test
        @DisplayName("negative matched when not impatient")
        void negative() {
            assertEquals("negative", detect("这双太贵了", List.of()));
        }

        @Test
        @DisplayName("positive matched when no impatient/negative")
        void positive() {
            assertEquals("positive", detect("嗯，挺好的", List.of()));
        }

        @Test
        @DisplayName("impatient wins over negative for '不要'")
        void impatientOverNegative() {
            assertEquals("impatient", detect("不要了", List.of()));
        }

        @Test
        @DisplayName("null utterance falls through to neutral")
        void nullUtterance() {
            assertEquals("neutral", detect(null, List.of()));
        }
    }

    @Nested
    @DisplayName("hesitation detection")
    class Hesitation {

        @Test
        @DisplayName("two assistant question-ending turns → hesitant")
        void twoQuestions() {
            var history = List.of(
                    assistant("想要缓震好一点的？"),
                    user("嗯"),
                    assistant("预算大概多少？")
            );
            assertEquals("hesitant", detect("嗯", history));
        }

        @Test
        @DisplayName("only one question-ending turn → neutral")
        void oneQuestion() {
            var history = List.of(
                    assistant("想要缓震好一点的？"),
                    user("嗯"),
                    assistant("好的，给你看看。")
            );
            assertEquals("neutral", detect("继续看看", history));
        }

        @Test
        @DisplayName("rule match on utterance beats hesitation signal")
        void ruleBeatsHesitation() {
            var history = List.of(
                    assistant("想要缓震好一点的？"),
                    assistant("预算大概多少？")
            );
            assertEquals("impatient", detect("快点", history));
        }
    }

    @Nested
    @DisplayName("neutral fallback")
    class Neutral {

        @Test
        @DisplayName("no rule hit and no hesitation → neutral")
        void neutralFallback() {
            assertEquals("neutral", detect("看看跑鞋", List.of()));
        }
    }
}
