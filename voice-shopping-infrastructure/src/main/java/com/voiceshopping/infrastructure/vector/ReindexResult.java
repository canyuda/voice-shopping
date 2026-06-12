package com.voiceshopping.infrastructure.vector;

/**
 * Result of a batch reindex operation (product or FAQ).
 */
public record ReindexResult(
        int totalProcessed,
        int successCount,
        int failCount
) {
    public static ReindexResult empty() {
        return new ReindexResult(0, 0, 0);
    }
}
