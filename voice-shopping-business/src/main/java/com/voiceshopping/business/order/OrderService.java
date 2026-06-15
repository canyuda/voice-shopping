package com.voiceshopping.business.order;

import com.voiceshopping.common.dto.PageInfo;
import com.voiceshopping.common.dto.order.OrderDTO;
import com.voiceshopping.common.dto.order.OrderItemDTO;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.OrderRecordRepository;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Order query service enforcing the subject-scoped filtering principle
 * (see CLAUDE.md "主体强制过滤原则" and the {@code order-query}
 * capability spec).
 * <p>
 * <strong>Design note (D8)</strong>: this service intentionally does NOT
 * depend on Sa-Token / {@code StpUtil}. {@code userId} and
 * {@code merchantId} are required parameters on every method — callers
 * (Controller / scheduled tasks / tests) MUST supply the subject
 * explicitly. This mirrors {@code SessionService} et al. and keeps
 * {@code voice-shopping-business} free of the web-layer auth framework.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRecordRepository orderRecordRepository;

    public OrderService(OrderRecordRepository orderRecordRepository) {
        this.orderRecordRepository = orderRecordRepository;
    }

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

    // ---- Mapping ----

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
}
