package com.voiceshopping.infrastructure.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Vector search operations for the {@code faq_entry} table via JdbcTemplate.
 * <p>
 * Supports platform-wide (merchantId=0) + merchant-private FAQ retrieval.
 * Similarity threshold: 0.75 for production queries, no threshold for debug.
 */
@Service
public class FaqVectorService {

    private static final Logger log = LoggerFactory.getLogger(FaqVectorService.class);
    private static final double SIMILARITY_THRESHOLD = 0.72;

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FaqVectorService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Upsert embedding vector for a FAQ entry.
     */
    public void upsertEmbedding(long faqId, float[] embedding, String embeddingText) {
        jdbc.update(
                "UPDATE faq_entry SET embedding = ?, embedding_text = ? WHERE id = ?",
                new PGvector(embedding),
                embeddingText,
                faqId
        );
    }

    /**
     * Find the best matching FAQ entry above the similarity threshold.
     * Searches both platform-wide (merchantId=0) and merchant-private entries.
     *
     * @return {@link Optional#empty()} if best similarity &lt; 0.75
     */
    public Optional<FaqSearchResult> searchBest(long merchantId, float[] queryVector) {
        String sql = """
                SELECT id, question, answer, category, tags,
                       1 - (embedding <=> ?) AS similarity
                FROM faq_entry
                WHERE merchant_id IN (0, ?)
                  AND embedding IS NOT NULL
                ORDER BY embedding <=> ?
                LIMIT 1
                """;

        List<FaqSearchResult> results = jdbc.query(sql, this::mapRow,
                new PGvector(queryVector), merchantId, new PGvector(queryVector));

        if (results.isEmpty()) {
            return Optional.empty();
        }

        FaqSearchResult best = results.getFirst();
        if (best.similarity() < SIMILARITY_THRESHOLD) {
            log.debug("FAQ best similarity {} below threshold {}, query for merchantId={}",
                    best.similarity(), SIMILARITY_THRESHOLD, merchantId);
            return Optional.empty();
        }

        return Optional.of(best);
    }

    /**
     * Find top-N matching FAQ entries without similarity threshold filtering.
     * Used for debug/admin interfaces.
     */
    public List<FaqSearchResult> searchTopN(long merchantId, float[] queryVector, int topN) {
        String sql = """
                SELECT id, question, answer, category, tags,
                       1 - (embedding <=> ?) AS similarity
                FROM faq_entry
                WHERE merchant_id IN (0, ?)
                  AND embedding IS NOT NULL
                ORDER BY embedding <=> ?
                LIMIT ?
                """;

        return jdbc.query(sql, this::mapRow,
                new PGvector(queryVector), merchantId, new PGvector(queryVector), topN);
    }

    private FaqSearchResult mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FaqSearchResult(
                rs.getLong("id"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getString("category"),
                parseJson(rs.getString("tags"), STRING_LIST_TYPE, Collections.emptyList()),
                rs.getDouble("similarity")
        );
    }

    private <T> T parseJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON: {}", json, e);
            return fallback;
        }
    }
}
