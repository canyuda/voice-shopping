package com.voiceshopping.infrastructure.vector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
     * Search products by cosine similarity with optional extra filter.
     *
     * @param queryVector embedding of the search query
     * @param extraFilter optional SQL WHERE fragment (e.g. "price <= ? AND attributes @> CAST(? AS jsonb)")
     * @param extraParams parameter values for the extraFilter placeholders
     * @param topK        max results to return
     * @return results ordered by similarity descending
     */
    public List<ProductSearchResult> search(
            float[] queryVector,
            String extraFilter,
            List<Object> extraParams,
            int topK) {

        // merchant_id filter is supplied by callers via extraFilter (see ScopeFilterBuilder).
        // Single vector param: SELECT distance AS embedding <=> ?, ORDER BY distance.
        // Planner expands the alias back to `embedding <=> ?` ASC → hits HNSW index.
        // similarity (1 - distance) is derived in mapRow, not in SQL.
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, category_l1, category_l2, price, image_urls, attributes,
                       embedding <=> ? AS distance
                FROM product
                WHERE status = 'ON_SALE' AND deleted_at IS NULL
                  AND embedding IS NOT NULL
                """);

        List<Object> params = new ArrayList<>();
        params.add(new PGvector(queryVector));
        if (extraFilter != null && !extraFilter.isBlank()) {
            sql.append(" AND ").append(extraFilter);
        }
        params.addAll(extraParams);
        // <=> 是 pgvector 的 cosine distance 运算符，越小越相似
        sql.append(" ORDER BY distance LIMIT ?");
        params.add(topK);

        if (log.isDebugEnabled()) {
            log.debug("Vector search full SQL: \n{}", getFullSql(sql, params));
        }

        return jdbc.query(sql.toString(), this::mapRow, params.toArray());
    }

    @NotNull
    private static String getFullSql(StringBuilder sql, List<Object> params) {
        String sqlStr = sql.toString();
        List<String> displayParams = params.stream()
                .map(p -> {
                    String s = String.valueOf(p);
                    return s.length() > 100 ? "<超长字段>" : s;
                })
                .toList();
        // Interpolate params into SQL for readability
        String fullSql = sqlStr;
        for (String dp : displayParams) {
            fullSql = fullSql.replaceFirst("\\?", dp);
        }
        return fullSql;
    }

    private ProductSearchResult mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        double distance = rs.getDouble("distance");
        return new ProductSearchResult(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category_l1"),
                rs.getString("category_l2"),
                rs.getBigDecimal("price"),
                parseJson(rs.getString("image_urls"), STRING_LIST_TYPE, Collections.emptyList()),
                parseJson(rs.getString("attributes"), OBJECT_MAP_TYPE, Collections.emptyMap()),
                1.0 - distance
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
