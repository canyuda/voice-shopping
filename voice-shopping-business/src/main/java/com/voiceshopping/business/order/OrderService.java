package com.voiceshopping.business.order;

import com.voiceshopping.business.behavior.UserPurchasedEvent;
import com.voiceshopping.business.scope.SessionScopeCache;
import com.voiceshopping.common.dto.PageInfo;
import com.voiceshopping.common.dto.order.OrderDTO;
import com.voiceshopping.common.dto.order.OrderItemDTO;
import com.voiceshopping.common.dto.order.PendingOrder;
import com.voiceshopping.common.dto.session.SessionScope;
import com.voiceshopping.common.exception.ForbiddenException;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.OrderRecordRepository;
import com.voiceshopping.infrastructure.repository.ProductRepository;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord.OrderItemJson;
import com.voiceshopping.infrastructure.repository.entity.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Order service — combines the read path (subject-scoped query, see CLAUDE.md
 * "主体强制过滤原则" and the {@code order-query} capability spec) with the
 * write path that drives the order placement state machine
 * ({@code order-placement-flow} capability spec).
 * <p>
 * The two halves coexist in one class by explicit user direction (do not
 * split). Method groups:
 *
 * <h3>Read path — query-only, subject-scoped</h3>
 * {@link #getForUser(Long, Long)} / {@link #listMine(Long, Pageable)} /
 * {@link #listForMerchant(Long, Pageable)}. {@code userId} / {@code merchantId}
 * are required parameters; this service does NOT depend on Sa-Token. The
 * underlying repository methods are derived queries that include
 * {@code AND user_id = ?} or {@code AND merchant_id = ?} so "forgetting the
 * subject" is a compile error rather than a runtime bypass. Mirrors
 * {@code SessionService} et al.
 *
 * <h3>Write path — preview / confirm / cancel</h3>
 * Drives {@code OrchestratorService.handleOrderConfirm}.
 * {@link #preview(String, Long, Long, int)} writes Redis {@code PendingOrder}
 * after validating product existence, session scope (defense-in-depth atop
 * the retrieval-time {@code ScopeFilterBuilder}), and stock; never mutates
 * stock. {@link #confirm(String)} runs inside a single transaction, performs
 * an atomic SQL stock decrement to prevent oversell, persists
 * {@link OrderRecord}, removes the Redis key, and publishes
 * {@link UserPurchasedEvent} so {@code AFTER_COMMIT} listeners drive the
 * profile and long-term memory writebacks. {@link #cancel(String)} is
 * idempotent and clears the Redis key only.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRecordRepository orderRecordRepository;
    private final ProductRepository productRepository;
    private final PendingOrderStore pendingOrderStore;
    private final SessionScopeCache sessionScopeCache;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRecordRepository orderRecordRepository,
                        ProductRepository productRepository,
                        PendingOrderStore pendingOrderStore,
                        SessionScopeCache sessionScopeCache,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRecordRepository = orderRecordRepository;
        this.productRepository = productRepository;
        this.pendingOrderStore = pendingOrderStore;
        this.sessionScopeCache = sessionScopeCache;
        this.eventPublisher = eventPublisher;
    }

    // =====================================================================
    // Read path (subject-scoped query) — see CLAUDE.md 主体强制过滤原则
    // =====================================================================

    /**
     * Fetch a single order on behalf of {@code userId}. Returns the DTO
     * if and only if the order belongs to that user; otherwise throws
     * {@link NotFoundException} with a generic message — the same
     * response is returned for "order does not exist" and "order belongs
     * to someone else", to avoid leaking existence.
     * <p>
     * On the miss branch the method performs an extra
     * {@code existsById} probe purely for diagnostic logging
     * ({@code exists=true} hints at a probe attempt; {@code exists=false}
     * is harmless). The probe never runs on the happy path.
     */
    public OrderDTO getForUser(Long userId, Long orderId) {
        Optional<OrderRecord> hit = orderRecordRepository.findByIdAndUserId(orderId, userId);
        if (hit.isPresent()) {
            return toDto(hit.get());
        }
        boolean exists = orderRecordRepository.existsById(orderId);
        log.warn("Order lookup miss: orderId={}, userId={}, exists={}", orderId, userId, exists);
        throw new NotFoundException("订单不存在");
    }

    /**
     * Page through orders owned by {@code userId}, latest first.
     */
    public PageInfo<OrderDTO> listMine(Long userId, Pageable pageable) {
        return toPageInfo(
                orderRecordRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable));
    }

    /**
     * Page through orders under {@code merchantId}. <strong>Internal
     * service method — not exposed via Controller.</strong> The caller
     * is responsible for confirming that {@code merchantId} is
     * legitimate (e.g. the caller is a merchant-admin background task
     * or a scheduled job). No role check is enforced here at this
     * stage.
     */
    public PageInfo<OrderDTO> listForMerchant(Long merchantId, Pageable pageable) {
        return toPageInfo(
                orderRecordRepository.findAllByMerchantIdOrderByCreatedAtDesc(merchantId, pageable));
    }

    // =====================================================================
    // Write path (preview / confirm / cancel)
    // =====================================================================

    /**
     * Build a {@link PendingOrder} preview for the upcoming confirmation.
     * <p>
     * Validation order — fail-fast, never silently degrade:
     * <ol>
     *   <li>Product exists (and not soft-deleted).</li>
     *   <li>Product's merchant is reachable from the current
     *       {@link SessionScope}; defense-in-depth atop the retrieval-time
     *       {@code ScopeFilterBuilder} since the order entry point bypasses
     *       it (the user spoke a product reference).</li>
     *   <li>Stock is sufficient at preview time (best-effort — the
     *       authoritative check is the atomic UPDATE in {@link #confirm}).</li>
     * </ol>
     * Stock is NOT decremented here. The Redis key is overwritten when an
     * existing pending order shares the same {@code sessionId}.
     */
    public PendingOrder preview(String sessionId, Long userId, Long productId, int quantity) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        Product product = productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));

        // Scope check — cache miss → SessionScope.platformWide(userId), which
        // has empty allowedMerchantIds and therefore "no merchant constraint";
        // platform-wide intentionally allows preview to proceed (consistent
        // with retrieval-time scope semantics).
        SessionScope scope = sessionScopeCache.get(sessionId)
                .orElseGet(() -> SessionScope.platformWide(userId));
        if (!scope.isPlatformWide()
                && !scope.allowedMerchantIds().contains(product.getMerchantId())) {
            throw new ForbiddenException("该商品不在当前会话范围内");
        }

        if (product.getStock() == null || product.getStock() < quantity) {
            throw new IllegalStateException("库存不足");
        }

        BigDecimal unitPrice = product.getPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        PendingOrder po = new PendingOrder(
                sessionId,
                userId,
                product.getMerchantId(),
                product.getId(),
                product.getName(),
                product.getSkuCode(),
                quantity,
                unitPrice,
                totalAmount);

        pendingOrderStore.put(po);
        log.info("PendingOrder previewed: sessionId={}, userId={}, productId={}, qty={}, total={}",
                sessionId, userId, productId, quantity, totalAmount);
        return po;
    }

    /**
     * Materialize the pending preview into a real {@link OrderRecord}.
     * <p>
     * Steps within a single transaction:
     * <ol>
     *   <li>Read pending — null is fail-fast ({@link IllegalStateException}).</li>
     *   <li>Atomic SQL stock decrement; rows-affected = 0 means stock was
     *       drained between preview and confirm, and we surface
     *       {@link IllegalStateException} <i>without</i> removing the Redis
     *       key — the user can immediately retry / cancel.</li>
     *   <li>Re-read product to assert merchant binding still matches the
     *       pending snapshot (rejects tampered Redis state).</li>
     *   <li>Persist {@link OrderRecord}. {@code agent_attribution=true} and
     *       {@code source_intent=ORDER_CONFIRM} are baked in.</li>
     *   <li>Remove Redis pending key.</li>
     *   <li>Publish {@link UserPurchasedEvent} <b>inside</b> the transaction
     *       so {@code @TransactionalEventListener(AFTER_COMMIT)} handlers
     *       are armed; rollback before commit kills the event.</li>
     * </ol>
     */
    @Transactional
    public OrderRecord confirm(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        PendingOrder po = pendingOrderStore.get(sessionId);
        if (po == null) {
            throw new IllegalStateException("没有待确认订单，或已过期");
        }

        int rows = productRepository.decrementStock(po.productId(), po.quantity());
        if (rows == 0) {
            // Stock drained or product disappeared. Do NOT delete the Redis
            // key — let the user follow up with cancel/retry on their own
            // turn (Orchestrator handles the friendly fallback wording).
            log.warn("confirm rejected — stock unavailable: sessionId={}, productId={}, qty={}",
                    sessionId, po.productId(), po.quantity());
            throw new IllegalStateException("库存不足");
        }

        Product product = productRepository.findById(po.productId())
                .orElseThrow(() -> new IllegalStateException("商品不存在"));
        if (!product.getMerchantId().equals(po.merchantId())) {
            // Tampered Redis state — abort the transaction so the
            // pre-decrement happens to roll back along with everything else.
            throw new IllegalStateException("商品归属异常");
        }

        OrderRecord record = new OrderRecord();
        record.setMerchantId(po.merchantId());
        record.setUserId(po.userId());
        record.setSessionId(sessionId);
        record.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        record.setItems(List.of(new OrderItemJson(
                po.productId(), po.productName(), po.unitPrice(), po.quantity())));
        record.setTotalAmount(po.totalAmount());
        record.setStatus("PAID");
        record.setAgentAttribution(true);
        record.setSourceIntent("ORDER_CONFIRM");
        Instant now = Instant.now();
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        OrderRecord saved = orderRecordRepository.save(record);
        pendingOrderStore.remove(sessionId);

        // Publish inside the transaction so AFTER_COMMIT listeners fire
        // only after the order row is durable. brand lives in attributes
        // JSONB ({"brand":"Asics"}); category uses the more stable
        // category_l1 column.
        String brand = extractBrand(product);
        String category = product.getCategoryL1();
        eventPublisher.publishEvent(new UserPurchasedEvent(
                this, po.userId(), category, brand, po.totalAmount(), sessionId));

        log.info("Order placed: orderId={}, orderNo={}, sessionId={}, userId={}, productId={}, total={}",
                saved.getId(), saved.getOrderNo(), sessionId, po.userId(),
                po.productId(), po.totalAmount());
        return saved;
    }

    /**
     * Discard the preview without writing anything to PG. Idempotent.
     */
    public void cancel(String sessionId) {
        pendingOrderStore.remove(sessionId);
    }

    // =====================================================================
    // Mapping helpers (read path)
    // =====================================================================

    /**
     * Adapt a {@link Page} of entities to a lean {@link PageInfo} of DTOs.
     * Spring Data's {@code Page} stays internal to the service; HTTP
     * responses see only {@code PageInfo}.
     */
    private PageInfo<OrderDTO> toPageInfo(Page<OrderRecord> page) {
        List<OrderDTO> dtos = page.getContent().stream().map(this::toDto).toList();
        return new PageInfo<>(
                dtos,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    private OrderDTO toDto(OrderRecord e) {
        List<OrderItemDTO> items = Optional.ofNullable(e.getItems())
                .orElseGet(Collections::emptyList)
                .stream()
                .map(item -> new OrderItemDTO(
                        item.productId(),
                        item.name(),
                        item.price(),
                        item.quantity()))
                .toList();

        Map<String, Object> aiContext = Optional.ofNullable(e.getAiContext())
                .orElseGet(Collections::emptyMap);

        return new OrderDTO(
                e.getId(),
                e.getMerchantId(),
                e.getUserId(),
                e.getSessionId(),
                e.getOrderNo(),
                items,
                e.getTotalAmount(),
                e.getStatus(),
                e.getAgentAttribution(),
                e.getSourceIntent(),
                aiContext,
                e.getReceiverName(),
                e.getReceiverPhone(),
                e.getReceiverAddr(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    /**
     * Pull {@code attributes.brand} out of the Product JSONB column.
     * Returns {@code null} when missing — listeners must tolerate.
     */
    private String extractBrand(Product product) {
        Map<String, Object> attrs = product.getAttributes();
        if (attrs == null) {
            return null;
        }
        Object brand = attrs.get("brand");
        return brand == null ? null : brand.toString();
    }
}
