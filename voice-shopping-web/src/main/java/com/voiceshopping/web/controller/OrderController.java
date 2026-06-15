package com.voiceshopping.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.voiceshopping.business.order.OrderService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.PageInfo;
import com.voiceshopping.common.dto.order.OrderDTO;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order query endpoints (user-facing).
 * <p>
 * The Controller takes the current login id from Sa-Token
 * ({@code StpUtil.getLoginIdAsLong()}) and passes it explicitly into
 * {@link OrderService} — this layer is the only one that knows about
 * Sa-Token (see {@code order-query} spec / design.md D8).
 * <p>
 * <strong>No merchant-side endpoint is exposed.</strong>
 * {@link OrderService#listForMerchant(Long, org.springframework.data.domain.Pageable)}
 * exists for internal callers (scheduled tasks / future merchant-admin
 * paths once a role system is in place) and is intentionally not
 * routed here.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Page through the current user's orders. Query params are bound by
     * Spring's default {@code PageableHandlerMethodArgumentResolver}
     * (defaults: {@code page=0}, {@code size=20}); ordering is enforced
     * server-side as {@code created_at DESC} (the {@code sort} query
     * param is ignored — see design.md D3).
     */
    @GetMapping("/mine")
    public ApiResult<PageInfo<OrderDTO>> listMine(Pageable pageable) {
        Long userId = StpUtil.getLoginIdAsLong();
        return ApiResult.ok(orderService.listMine(userId, pageable));
    }

    /**
     * Fetch one of the current user's orders. Returns 404 with a
     * generic message both for "no such order" and "order belongs to
     * another user" — leaking that distinction would expose order id
     * existence (see design.md D5).
     */
    @GetMapping("/{orderId}")
    public ApiResult<OrderDTO> getOne(@PathVariable Long orderId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return ApiResult.ok(orderService.getForUser(userId, orderId));
    }
}
