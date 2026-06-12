package com.voiceshopping.infrastructure.vector;

import java.util.List;

/**
 * Immutable DTO for FAQ vector search results.
 * Contains FAQ entry info + cosine similarity score.
 */
public record FaqSearchResult(
        long id,
        String question,
        String answer,
        String category,
        List<String> tags,
        double similarity
) {}
