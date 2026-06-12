package com.voiceshopping.ai.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into sentences by Chinese/English punctuation marks.
 * Punctuation is retained at the end of each sentence.
 */
public final class SentenceSplitter {

    private static final Pattern SENTENCE_PATTERN = Pattern.compile(".*?[。！？；.!?\n]");

    private SentenceSplitter() {
    }

    /**
     * Split text by punctuation. Empty or blank input returns empty list.
     *
     * @param text input text
     * @return list of sentences with trailing punctuation preserved
     */
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            sentences.add(matcher.group());
            lastEnd = matcher.end();
        }

        // Remaining text without trailing punctuation
        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd);
            if (!remaining.isBlank()) {
                sentences.add(remaining);
            }
        }

        return sentences;
    }
}
