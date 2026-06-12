package com.voiceshopping.web.dto;

/**
 * Response body for POST /api/v1/admin/reindex and /api/v1/admin/faq-reindex
 */
public record ReindexResponse(
        int totalProcessed,
        int successCount,
        int failCount
) {}
