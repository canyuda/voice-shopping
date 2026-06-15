package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link OrderRecord}.
 * <p>
 * <strong>主体强制过滤原则</strong>：业务 Service 禁止直接调用
 * {@code findById(id)} / {@code findAll()} —— 所有订单查询必须走带主体维度
 * （{@code AndUserId} / {@code AndMerchantId}）的派生查询方法。详见
 * {@code CLAUDE.md} 同名章节与 {@code order-query} capability spec。
 * <p>
 * 派生查询的方法名后缀（{@code AndUserId} / {@code AndMerchantId}）会让
 * Spring Data JPA 自动生成 {@code AND user_id = ?} / {@code AND merchant_id = ?}
 * 子句，从编译期就拦下"忘传身份"的越权写法。
 */
public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    /**
     * Find an order by its id under the given user. Use this instead of
     * {@code findById} for any user-facing order lookup.
     */
    Optional<OrderRecord> findByIdAndUserId(Long id, Long userId);

    /**
     * Find an order by its id under the given merchant. For internal
     * merchant-side use only — not exposed via Controller (see
     * {@code OrderService#listForMerchant}).
     */
    Optional<OrderRecord> findByIdAndMerchantId(Long id, Long merchantId);

    /**
     * Page through a user's orders, latest first.
     */
    Page<OrderRecord> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Page through a merchant's orders, latest first. Internal use only.
     */
    Page<OrderRecord> findAllByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);
}
