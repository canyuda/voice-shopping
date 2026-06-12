package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.FaqEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Basic CRUD repository for {@link FaqEntry}.
 * Vector search uses JdbcTemplate in
 * {@link com.voiceshopping.infrastructure.vector.FaqVectorService}.
 */
public interface FaqEntryRepository extends JpaRepository<FaqEntry, Long> {

    /**
     * Returns platform-wide (merchantId=0) plus merchant-private entries.
     */
    List<FaqEntry> findByMerchantIdIn(List<Long> merchantIds);

    List<FaqEntry> findByEmbeddingTextNotNull();
}
