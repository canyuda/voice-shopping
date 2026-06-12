package com.voiceshopping.infrastructure.vector;

import com.voiceshopping.infrastructure.repository.FaqEntryRepository;
import com.voiceshopping.infrastructure.repository.ProductRepository;
import com.voiceshopping.infrastructure.repository.entity.FaqEntry;
import com.voiceshopping.infrastructure.repository.entity.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Batch reindex service for product and FAQ embeddings.
 * Uses virtual threads for concurrent DashScope API calls.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);
    private static final int BATCH_SIZE = 25;

    private final ProductRepository productRepository;
    private final FaqEntryRepository faqEntryRepository;
    private final EmbeddingService embeddingService;
    private final ProductVectorService productVectorService;
    private final FaqVectorService faqVectorService;

    public ReindexService(
            ProductRepository productRepository,
            FaqEntryRepository faqEntryRepository,
            EmbeddingService embeddingService,
            ProductVectorService productVectorService,
            FaqVectorService faqVectorService) {
        this.productRepository = productRepository;
        this.faqEntryRepository = faqEntryRepository;
        this.embeddingService = embeddingService;
        this.productVectorService = productVectorService;
        this.faqVectorService = faqVectorService;
    }

    /**
     * Reindex all products: build embedding text → batch embed → upert vectors.
     */
    public ReindexResult reindexProducts() {
        List<Product> products = productRepository.findByEmbeddingTextNotNullAndDeletedAtIsNull();
        if (products.isEmpty()) {
            log.info("No products to reindex");
            return ReindexResult.empty();
        }

        log.info("Starting product reindex: {} items", products.size());
        return processBatches(
                products,
                this::buildProductEmbeddingText,
                productVectorService::upsertEmbedding,
                "product"
        );
    }

    /**
     * Reindex all FAQ entries: build embedding text → batch embed → upsert vectors.
     */
    public ReindexResult reindexFaqs() {
        List<FaqEntry> faqs = faqEntryRepository.findByEmbeddingTextNotNull();
        if (faqs.isEmpty()) {
            log.info("No FAQs to reindex");
            return ReindexResult.empty();
        }

        log.info("Starting FAQ reindex: {} items", faqs.size());
        return processBatches(
                faqs,
                this::buildFaqEmbeddingText,
                faqVectorService::upsertEmbedding,
                "faq"
        );
    }

    /**
     * Build embedding text for a product.
     * sellingPoints is doubled for semantic weight.
     */
    private String buildProductEmbeddingText(Product p) {
        return Stream.of(
                        p.getName(),
                        p.getCategoryL1(),
                        p.getCategoryL2(),
                        p.getDescription(),
                        p.getSellingPoints(),
                        p.getSellingPoints() // doubled for weight
                )
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    /**
     * Build embedding text for a FAQ entry: question + answer + category.
     */
    private String buildFaqEmbeddingText(FaqEntry f) {
        return Stream.of(
                        f.getQuestion(),
                        f.getAnswer(),
                        f.getCategory()
                )
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    /**
     * Generic batch processing pipeline using virtual threads.
     * Splits items into batches of 25, runs batches concurrently via structured concurrency,
     * each batch: embed → upsert per item.
     */
    private <T> ReindexResult processBatches(
            List<T> items,
            java.util.function.Function<T, String> textExtractor,
            TriConsumer<Long, float[], String> upserter,
            String label) {

        // Build (id, embeddingText) pairs
        record ItemText(long id, String text) {}
        List<ItemText> prepared = items.stream()
                .map(item -> {
                    long id = extractId(item);
                    String text = textExtractor.apply(item);
                    return text.isBlank() ? null : new ItemText(id, text);
                })
                .filter(Objects::nonNull)
                .toList();

        if (prepared.isEmpty()) {
            return ReindexResult.empty();
        }

        // Split into batches
        List<List<ItemText>> batches = partition(prepared, BATCH_SIZE);
        log.info("{} reindex: {} items split into {} batches", label, prepared.size(), batches.size());

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < batches.size(); i++) {
            List<ItemText> batch = batches.get(i);
            try {
                List<String> texts = batch.stream().map(ItemText::text).toList();
                List<float[]> embeddings = embeddingService.embedBatch(texts);

                for (int j = 0; j < batch.size(); j++) {
                    try {
                        ItemText item = batch.get(j);
                        upserter.accept(item.id(), embeddings.get(j), item.text());
                        successCount++;
                    } catch (Exception e) {
                        log.error("Failed to upsert {} embedding for id={}: {}",
                                label, batch.get(j).id(), e.getMessage());
                        failCount++;
                    }
                }
            } catch (Exception e) {
                log.error("{} batch {}/{} failed entirely: {}",
                        label, i + 1, batches.size(), e.getMessage());
                failCount += batch.size();
            }
        }

        log.info("{} reindex complete: success={}, fail={}", label, successCount, failCount);
        return new ReindexResult(prepared.size(), successCount, failCount);
    }

    @SuppressWarnings("unchecked")
    private <T> long extractId(T item) {
        if (item instanceof Product p) return p.getId();
        if (item instanceof FaqEntry f) return f.getId();
        throw new IllegalArgumentException("Unsupported item type: " + item.getClass());
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    @FunctionalInterface
    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
