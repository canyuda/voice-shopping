package com.voiceshopping.business.rec;

import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.infrastructure.vector.ProductVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * First-stage candidate retrieval: fetch top-N products by cosine similarity
 * with SQL filter constraints. Results are passed downstream for profile-based
 * reranking and reason generation.
 */
@Service
public class RecommendCandidatesService {

    private static final Logger log = LoggerFactory.getLogger(RecommendCandidatesService.class);

    private final ProductVectorService vectorService;

    public RecommendCandidatesService(ProductVectorService vectorService) {
        this.vectorService = vectorService;
    }

    /**
     * Retrieve candidate products by vector similarity + SQL filter.
     *
     * @param queryVector embedding of the search query (reusable across fallback retries)
     * @param filter      SQL WHERE fragment + params from SqlFilterBuilder
     * @param topN        max candidates to return (typically 20)
     * @return list of RecommendedItem with matchScore = cosine similarity
     */
    public List<RecommendedItem> fetchCandidates(float[] queryVector, Filter filter, int topN) {
        log.debug("Fetching top {} candidates with filter: {}", topN, filter.clause());

        var results = vectorService.search(
                queryVector,
                filter.clause(),
                filter.params(),
                topN);

        return results.stream()
                .map(r -> new RecommendedItem(
                        r.productId(),
                        r.name(),
                        r.price(),
                        "",
                        r.similarity(),
                        r.attributes()))
                .toList();
    }
}
