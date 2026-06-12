package com.voiceshopping.infrastructure.vector;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Wraps DashScope text-embedding-v3 API.
 * Supports single and batch embedding with concurrency control and retry.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int MAX_BATCH_SIZE = 25;
    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final int EMBEDDING_DIMENSION = 1024;

    private final TextEmbedding client;
    private final String modelName;
    private final Semaphore semaphore;

    public EmbeddingService(
            @Value("${dashscope.api-key}") String apiKey,
            @Value("${voice-shopping.embedding.model:text-embedding-v3}") String modelName,
            @Value("${voice-shopping.embedding.concurrency:5}") int concurrency) {
        this.client = new TextEmbedding();
        this.modelName = modelName;
        this.semaphore = new Semaphore(concurrency);
        log.info("EmbeddingService initialized: model={}, concurrency={}", modelName, concurrency);
    }

    /**
     * Embed a single text string.
     *
     * @throws EmbeddingException on API failure after retries
     */
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        List<float[]> results = embedBatch(Collections.singletonList(text));
        return results.getFirst();
    }

    /**
     * Batch embed up to 25 texts in a single DashScope API call.
     * Returns embeddings in the same order as input texts.
     *
     * @throws IllegalArgumentException if texts size exceeds 25
     * @throws EmbeddingException       on API failure after retries
     */
    public List<float[]> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            return Collections.emptyList();
        }
        if (texts.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Batch size " + texts.size() + " exceeds max " + MAX_BATCH_SIZE);
        }

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while acquiring semaphore", e);
        }

        try {
            return doEmbedBatchWithRetry(texts);
        } finally {
            semaphore.release();
        }
    }

    private List<float[]> doEmbedBatchWithRetry(List<String> texts) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doCallEmbeddingApi(texts);
            } catch (Exception e) {
                lastException = e;
                log.warn("Embedding API call failed (attempt {}/{}): {}",
                        attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleepWithBackoff(attempt);
                }
            }
        }

        throw new EmbeddingException(
                "DashScope embedding API failed after " + MAX_RETRIES + " retries: "
                        + lastException.getMessage(), lastException);
    }

    private List<float[]> doCallEmbeddingApi(List<String> texts)
            throws NoApiKeyException, InputRequiredException {

        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model(modelName)
                .texts(texts)
                .textType(TextEmbeddingParam.TextType.DOCUMENT)
                .build();

        TextEmbeddingResult result = client.call(param);

        List<TextEmbeddingResultItem> items =
                result.getOutput().getEmbeddings();

        List<float[]> embeddings = new ArrayList<>(items.size());
        for (TextEmbeddingResultItem item : items) {
            embeddings.add(toPrimitiveArray(item.getEmbedding()));
        }
        return embeddings;
    }

    private static float[] toPrimitiveArray(List<Double> doubles) {
        float[] floats = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            floats[i] = doubles.get(i).floatValue();
        }
        return floats;
    }

    private void sleepWithBackoff(int attempt) {
        long delay = (long) (BASE_RETRY_DELAY_MS * Math.pow(2, attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted during retry backoff", e);
        }
    }
}
