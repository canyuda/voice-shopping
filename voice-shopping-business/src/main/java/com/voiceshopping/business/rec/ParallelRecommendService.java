package com.voiceshopping.business.rec;

import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import com.voiceshopping.infrastructure.vector.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Parallelized variant of {@link RecommendOrchestrator#recommend} that fans out
 * the profile-load leg and the candidate-retrieval leg concurrently using
 * {@link CompletableFuture}.
 * <p>
 * The two legs are independent:
 * <ul>
 *   <li><b>Profile leg</b>: depends only on userId.</li>
 *   <li><b>Candidates leg</b>: buildQuery → embed → buildFilter → retrieve, depends only on utterance + slots.</li>
 * </ul>
 * After both finish, rerank → take Top K → attachReasons runs serially —
 * identical to the orchestrator.
 * <p>
 * Fallback semantics (budget +30%, drop categoryL2) are reused via
 * {@link RecommendCandidateRetriever}, so this service's results are equivalent
 * to {@link RecommendOrchestrator#recommend} on the same inputs (LLM-generated
 * reason text aside).
 */
@Service
public class ParallelRecommendService {

    private static final Logger log = LoggerFactory.getLogger(ParallelRecommendService.class);
    private static final int FINAL_TOP_K = 3;

    private final UserProfileService profileService;
    private final EmbeddingService embeddingService;
    private final RecommendCandidateRetriever retriever;
    private final ProfileReranker reranker;
    private final RecommendReasonService reasonService;

    public ParallelRecommendService(UserProfileService profileService,
                                    EmbeddingService embeddingService,
                                    RecommendCandidateRetriever retriever,
                                    ProfileReranker reranker,
                                    RecommendReasonService reasonService) {
        this.profileService = profileService;
        this.embeddingService = embeddingService;
        this.retriever = retriever;
        this.reranker = reranker;
        this.reasonService = reasonService;
    }

    /**
     * Execute the recommendation pipeline with profile load and candidate
     * retrieval running in parallel.
     */
    public RecommendResult recommend(String sessionId,
                                     Long userId,
                                     String utterance,
                                     Map<String, Object> slots) {
        log.info("Parallel recommendation request: userId={}, utterance={}, slots={}", userId, utterance, slots);

        CompletableFuture<UserProfileSnapshot> profileF = CompletableFuture.supplyAsync(() ->
                userId != null ? profileService.load(userId) : null);

        CompletableFuture<List<RecommendedItem>> candidatesF = CompletableFuture.supplyAsync(() -> {
            String query = retriever.buildQuery(utterance, slots);
            log.debug("Embedding query: {}", query);
            float[] queryVector = embeddingService.embed(query);
            Filter filter = retriever.buildFilter(slots);
            return retriever.retrieve(queryVector, filter, slots);
        });

        try {
            return profileF.thenCombine(candidatesF, (profile, candidates) -> {
                if (candidates.isEmpty()) {
                    log.info("No candidates found after all fallbacks");
                    return RecommendResult.EMPTY;
                }

                List<RecommendedItem> reranked = reranker.rerank(candidates, profile, slots);
                List<RecommendedItem> topK = reranked.stream()
                        .limit(FINAL_TOP_K)
                        .collect(Collectors.toList());

                String userNeeds = utterance + "; 槽位：" + slots;
                List<RecommendedItem> withReasons = reasonService.attachReasons(sessionId, userNeeds, topK);

                return new RecommendResult(withReasons, "professional");
            }).join();
        } catch (CompletionException e) {
            // Unwrap the original cause to preserve fail-fast semantics.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Parallel recommend failed: " + cause.getMessage(), cause);
        }
    }
}
