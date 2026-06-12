package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Basic CRUD repository for {@link Product}.
 * Vector operations (embedding read/write/search) use JdbcTemplate in
 * {@link com.voiceshopping.infrastructure.vector.ProductVectorService}.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByMerchantIdAndStatusAndDeletedAtIsNull(Long merchantId, String status);

    List<Product> findByMerchantIdAndNameContainingAndDeletedAtIsNull(Long merchantId, String keyword);

    List<Product> findByMerchantIdAndDeletedAtIsNull(Long merchantId);

    List<Product> findByDeletedAtIsNull();

    /**
     * For reindex: all active products with non-null embedding source text.
     */
    List<Product> findByEmbeddingTextNotNullAndDeletedAtIsNull();
}
