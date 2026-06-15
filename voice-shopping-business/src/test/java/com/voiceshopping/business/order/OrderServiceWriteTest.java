package com.voiceshopping.business.order;

import com.voiceshopping.business.behavior.UserPurchasedEvent;
import com.voiceshopping.business.scope.SessionScopeCache;
import com.voiceshopping.common.dto.order.PendingOrder;
import com.voiceshopping.common.dto.session.SessionScope;
import com.voiceshopping.common.exception.ForbiddenException;
import com.voiceshopping.infrastructure.repository.OrderRecordRepository;
import com.voiceshopping.infrastructure.repository.ProductRepository;
import com.voiceshopping.infrastructure.repository.entity.OrderRecord;
import com.voiceshopping.infrastructure.repository.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for the write path of {@link OrderService}: preview,
 * confirm, cancel. Mirrors {@code order-placement-flow/spec.md} scenarios.
 * The query path lives in a separate test (see the {@code order-query} change).
 */
class OrderServiceWriteTest {

    private static final String SESSION_ID = "sess-1";
    private static final Long USER_ID = 100L;
    private static final Long MERCHANT_ID = 1L;
    private static final Long PRODUCT_ID = 8821L;

    private OrderRecordRepository orderRecordRepository;
    private ProductRepository productRepository;
    private PendingOrderStore pendingOrderStore;
    private SessionScopeCache sessionScopeCache;
    private ApplicationEventPublisher eventPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRecordRepository = mock(OrderRecordRepository.class);
        productRepository = mock(ProductRepository.class);
        pendingOrderStore = mock(PendingOrderStore.class);
        sessionScopeCache = mock(SessionScopeCache.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        orderService = new OrderService(
                orderRecordRepository, productRepository, pendingOrderStore,
                sessionScopeCache, eventPublisher);
    }

    private Product sampleProduct(Long id, Long merchantId, int stock, BigDecimal price, String brand) {
        Product p = new Product();
        p.setId(id);
        p.setMerchantId(merchantId);
        p.setSkuCode("SKU-" + id);
        p.setName("Asics GEL-Contend 9");
        p.setCategoryL1("跑鞋");
        p.setStock(stock);
        p.setPrice(price);
        Map<String, Object> attrs = new HashMap<>();
        if (brand != null) {
            attrs.put("brand", brand);
        }
        p.setAttributes(attrs);
        return p;
    }

    // =====================================================================
    // preview
    // =====================================================================

    @Test
    void preview_writesPendingAndDoesNotMutateStock() {
        Product product = sampleProduct(PRODUCT_ID, MERCHANT_ID, 5,
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(sessionScopeCache.get(SESSION_ID))
                .thenReturn(Optional.of(new SessionScope(USER_ID, List.of(MERCHANT_ID), null)));

        PendingOrder po = orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1);

        ArgumentCaptor<PendingOrder> captor = ArgumentCaptor.forClass(PendingOrder.class);
        verify(pendingOrderStore).put(captor.capture());
        PendingOrder stored = captor.getValue();
        assertThat(stored.productId()).isEqualTo(PRODUCT_ID);
        assertThat(stored.unitPrice()).isEqualByComparingTo("479.00");
        assertThat(stored.totalAmount()).isEqualByComparingTo("479.00");
        assertThat(stored.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(po).isEqualTo(stored);
        // Stock untouched.
        assertThat(product.getStock()).isEqualTo(5);
        verify(productRepository, never()).decrementStock(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void preview_failsFastWhenProductMissing() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("商品不存在");

        verifyNoInteractions(pendingOrderStore);
    }

    @Test
    void preview_rejectsCrossMerchantWhenScopeIsRestricted() {
        Product foreign = sampleProduct(PRODUCT_ID, 99L, 5, // belongs to merchant 99
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(foreign));
        when(sessionScopeCache.get(SESSION_ID))
                .thenReturn(Optional.of(new SessionScope(USER_ID, List.of(MERCHANT_ID), null)));

        assertThatThrownBy(() -> orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("不在当前会话范围内");

        verifyNoInteractions(pendingOrderStore);
    }

    @Test
    void preview_allowsAcrossMerchantWhenScopeIsPlatformWide() {
        Product foreign = sampleProduct(PRODUCT_ID, 99L, 5,
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(foreign));
        // Platform-wide scope (cache miss fallback).
        when(sessionScopeCache.get(SESSION_ID)).thenReturn(Optional.empty());

        PendingOrder po = orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1);
        assertThat(po.merchantId()).isEqualTo(99L);
    }

    @Test
    void preview_failsWhenStockInsufficient() {
        Product zero = sampleProduct(PRODUCT_ID, MERCHANT_ID, 0,
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(zero));
        when(sessionScopeCache.get(SESSION_ID))
                .thenReturn(Optional.of(new SessionScope(USER_ID, List.of(MERCHANT_ID), null)));

        assertThatThrownBy(() -> orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存不足");

        verifyNoInteractions(pendingOrderStore);
    }

    @Test
    void preview_overwritesExistingPendingOnRepeatedCall() {
        Product p = sampleProduct(PRODUCT_ID, MERCHANT_ID, 5,
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(sessionScopeCache.get(SESSION_ID))
                .thenReturn(Optional.of(new SessionScope(USER_ID, List.of(MERCHANT_ID), null)));

        orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1);
        orderService.preview(SESSION_ID, USER_ID, PRODUCT_ID, 1);

        // PendingOrderStore.put called twice — last write wins (Redis SET overwrites).
        verify(pendingOrderStore, times(2)).put(any(PendingOrder.class));
    }

    // =====================================================================
    // confirm
    // =====================================================================

    private PendingOrder samplePending() {
        return new PendingOrder(
                SESSION_ID, USER_ID, MERCHANT_ID, PRODUCT_ID,
                "Asics", "SKU-8821", 1,
                new BigDecimal("479.00"), new BigDecimal("479.00"));
    }

    @Test
    void confirm_persistsOrderAndPublishesEvent() {
        PendingOrder pending = samplePending();
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(pending);
        when(productRepository.decrementStock(PRODUCT_ID, 1)).thenReturn(1);
        Product product = sampleProduct(PRODUCT_ID, MERCHANT_ID, 4,
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(orderRecordRepository.save(any(OrderRecord.class)))
                .thenAnswer(inv -> {
                    OrderRecord r = inv.getArgument(0);
                    r.setId(7777L);
                    return r;
                });

        OrderRecord saved = orderService.confirm(SESSION_ID);

        // Order written with the right shape.
        assertThat(saved.getStatus()).isEqualTo("PAID");
        assertThat(saved.getAgentAttribution()).isTrue();
        assertThat(saved.getSourceIntent()).isEqualTo("ORDER_CONFIRM");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).productId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getOrderNo()).hasSize(32); // UUID without dashes

        // Event published with sessionId and brand pulled from attributes.
        ArgumentCaptor<UserPurchasedEvent> evCaptor = ArgumentCaptor.forClass(UserPurchasedEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        UserPurchasedEvent ev = evCaptor.getValue();
        assertThat(ev.getUserId()).isEqualTo(USER_ID);
        assertThat(ev.getCategory()).isEqualTo("跑鞋");
        assertThat(ev.getBrand()).isEqualTo("Asics");
        assertThat(ev.getAmount()).isEqualByComparingTo("479.00");
        assertThat(ev.getSessionId()).isEqualTo(SESSION_ID);

        // Pending removed.
        verify(pendingOrderStore).remove(SESSION_ID);
    }

    @Test
    void confirm_failsFastWhenNoPending() {
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.confirm(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有待确认订单");

        verifyNoInteractions(productRepository, orderRecordRepository, eventPublisher);
    }

    @Test
    void confirm_keepsPendingAndThrowsWhenStockExhausted() {
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());
        when(productRepository.decrementStock(PRODUCT_ID, 1)).thenReturn(0);

        assertThatThrownBy(() -> orderService.confirm(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存不足");

        // No order written, no event published, pending NOT removed —
        // the user's next utterance will retry / cancel.
        verify(orderRecordRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(pendingOrderStore, never()).remove(any());
    }

    @Test
    void confirm_throwsWhenMerchantBindingMismatch() {
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());
        when(productRepository.decrementStock(PRODUCT_ID, 1)).thenReturn(1);
        // Tampered: PendingOrder said merchantId=1, product is now under merchant 99.
        Product tampered = sampleProduct(PRODUCT_ID, 99L, 4,
                new BigDecimal("479.00"), "Asics");
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(tampered));

        assertThatThrownBy(() -> orderService.confirm(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("商品归属异常");

        verify(orderRecordRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        // Transaction abort propagates upward — caller's @Transactional rolls back
        // the decrement (Spring tx test contract).
    }

    @Test
    void confirm_handlesProductWithoutBrandAttribute() {
        when(pendingOrderStore.get(SESSION_ID)).thenReturn(samplePending());
        when(productRepository.decrementStock(PRODUCT_ID, 1)).thenReturn(1);
        Product noBrand = sampleProduct(PRODUCT_ID, MERCHANT_ID, 4,
                new BigDecimal("479.00"), null);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(noBrand));
        when(orderRecordRepository.save(any(OrderRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.confirm(SESSION_ID);

        ArgumentCaptor<UserPurchasedEvent> evCaptor = ArgumentCaptor.forClass(UserPurchasedEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        // brand is null but no NPE; downstream listener tolerates.
        assertThat(evCaptor.getValue().getBrand()).isNull();
    }

    // =====================================================================
    // cancel
    // =====================================================================

    @Test
    void cancel_removesPending() {
        orderService.cancel(SESSION_ID);
        verify(pendingOrderStore).remove(SESSION_ID);
    }

    @Test
    void cancel_isIdempotent() {
        // PendingOrderStore.remove is idempotent — call cancel twice safely.
        orderService.cancel(SESSION_ID);
        orderService.cancel(SESSION_ID);
        verify(pendingOrderStore, times(2)).remove(SESSION_ID);
    }
}
