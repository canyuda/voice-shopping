package com.voiceshopping.infrastructure.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Vector write/search operations for the {@code product} table via JdbcTemplate.
 * <p>
 * All embedding operations use native SQL with pgvector operators.
 * JPA Entity is NOT used for vector fields.
 */
@Service
public class ProductVectorService {

    private static final Logger log = LoggerFactory.getLogger(ProductVectorService.class);

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductVectorService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Upsert embedding vector and source text for a product.
     */
    public void upsertEmbedding(long productId, float[] embedding, String embeddingText) {
        jdbc.update(
                "UPDATE product SET embedding = ?, embedding_text = ? WHERE id = ?",
                new PGvector(embedding),
                embeddingText,
                productId
        );
    }

    /**
     * Search products by cosine similarity with optional filters.
     *
     * @param merchantId       required tenant filter
     * @param queryVector      embedding of the search query
     * @param topK             max results to return
     * @param minPrice         optional lower price bound (inclusive)
     * @param maxPrice         optional upper price bound (inclusive)
     * @param attributeFilters optional JSONB attribute containment filter
     * @return results ordered by similarity descending
     */
    public List<ProductSearchResult> search(
            long merchantId,
            float[] queryVector,
            int topK,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Map<String, Object> attributeFilters) {

        StringBuilder sql = new StringBuilder("""
                SELECT id, name, category_l1, category_l2, price, image_urls, attributes,
                       1 - (embedding <=> ?) AS similarity
                FROM product
                WHERE merchant_id = ?
                  AND deleted_at IS NULL
                  AND embedding IS NOT NULL
                """);

        List<Object> params = new ArrayList<>();
        params.add(new PGvector(queryVector));
        params.add(merchantId);

        if (minPrice != null) {
            sql.append(" AND price >= ?");
            params.add(minPrice);
        }
        if (maxPrice != null) {
            sql.append(" AND price <= ?");
            params.add(maxPrice);
        }
        if (attributeFilters != null && !attributeFilters.isEmpty()) {
            sql.append(" AND attributes @> CAST(? AS jsonb)");
            try {
                params.add(objectMapper.writeValueAsString(attributeFilters));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize attribute filters", e);
            }
        }

        sql.append(" ORDER BY similarity DESC LIMIT ?");
        params.add(topK);

        return jdbc.query(sql.toString(), this::mapRow, params.toArray());
    }

    private ProductSearchResult mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductSearchResult(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category_l1"),
                rs.getString("category_l2"),
                rs.getBigDecimal("price"),
                parseJson(rs.getString("image_urls"), STRING_LIST_TYPE, Collections.emptyList()),
                parseJson(rs.getString("attributes"), OBJECT_MAP_TYPE, Collections.emptyMap()),
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
