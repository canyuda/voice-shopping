package com.voiceshopping.infrastructure.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA entity for the {@code order_record} table.
 * <p>
 * Order records carry both {@code merchantId} and {@code userId} subject
 * dimensions — see CLAUDE.md "主体强制过滤原则" and the
 * {@code order-query} capability spec for the strict filtering rule.
 * <p>
 * The {@code items} JSONB column is mapped to {@link OrderItemJson} for
 * compile-time field checking; {@code aiContext} stays loose-typed
 * ({@code Map<String, Object>}) since RecAgent's schema still evolves.
 */
@Entity
@Table(name = "order_record")
@Data
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** UUID string; mapped as String to align with V3 migration (session.id became varchar). */
    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<OrderItemJson> items = new ArrayList<>();

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /** allowed: CREATED / PAID / SHIPPED / DELIVERED / CANCELLED / REFUNDED. */
    @Column(nullable = false, length = 16)
    private String status = "CREATED";

    @Column(name = "agent_attribution", nullable = false)
    private Boolean agentAttribution = false;

    /** Source intent label, e.g. PRODUCT_RECOMMENDATION / PRODUCT_COMPARE. */
    @Column(name = "source_intent", length = 32)
    private String sourceIntent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_context", columnDefinition = "jsonb")
    private Map<String, Object> aiContext = new HashMap<>();

    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;

    @Column(name = "receiver_addr", columnDefinition = "TEXT")
    private String receiverAddr;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * One line item within {@link OrderRecord#getItems()}. Schema mirrors the
     * V1 migration's {@code COMMENT ON COLUMN order_record.items} contract.
     */
    public record OrderItemJson(
            Long productId,
            String name,
            BigDecimal price,
            Integer quantity
    ) {
    }
}
