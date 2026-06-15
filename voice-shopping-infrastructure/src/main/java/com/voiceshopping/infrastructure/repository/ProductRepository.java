package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Atomic stock decrement. Single SQL statement so the row-level lock plus
     * the {@code stock >= :qty} predicate guarantee no oversell — even under
     * concurrent confirms on the last unit.
     * <p>
     * Returns the number of rows affected: {@code 1} on success, {@code 0}
     * when the product is missing, soft-deleted, or has insufficient stock.
     * Callers MUST treat {@code 0} as a fail-fast {@code IllegalStateException}.
     * <p>
     * MUST be invoked from inside a {@code @Transactional} context — the
     * surrounding transaction owns the write barrier and supplies the
     * necessary commit/rollback semantics for downstream
     * {@code @TransactionalEventListener(AFTER_COMMIT)} listeners.
     */
    @Modifying
    @Query(value = "UPDATE product SET stock = stock - :qty " +
                   "WHERE id = :productId AND stock >= :qty AND deleted_at IS NULL",
           nativeQuery = true)
    int decrementStock(@Param("productId") Long productId, @Param("qty") int qty);
}
