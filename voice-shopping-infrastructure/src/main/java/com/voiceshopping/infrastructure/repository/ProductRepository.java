package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Scope-aware id batch lookup. When {@code merchantIds} is null or empty,
     * callers should prefer {@link #findAllById(Iterable)} directly — this
     * query enforces a non-empty scope and always applies
     * {@code deleted_at IS NULL}.
     */
    @Query("SELECT p FROM Product p WHERE p.id IN :ids AND p.merchantId IN :merchantIds AND p.deletedAt IS NULL")
    List<Product> findByIdInWithScope(@Param("ids") List<Long> ids,
                                      @Param("merchantIds") List<Long> merchantIds);
}
