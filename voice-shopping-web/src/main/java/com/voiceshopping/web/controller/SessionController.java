package com.voiceshopping.web.controller;

import com.voiceshopping.business.scope.SessionScopeCache;
import com.voiceshopping.business.session.SessionService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.session.Channel;
import com.voiceshopping.common.dto.session.SessionScope;
import com.voiceshopping.common.dto.session.StartSessionRequest;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.ProductRepository;
import com.voiceshopping.infrastructure.repository.entity.Product;
import com.voiceshopping.web.auth.CurrentUser;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Session lifecycle endpoints. {@code /start} is the explicit entry point that
 * resolves a {@link com.voiceshopping.common.dto.session.Channel} into a
 * {@link SessionScope}, persists the session row idempotently, and writes the
 * scope side-cache.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final CurrentUser currentUser;
    private final SessionService sessionService;
    private final ProductRepository productRepository;
    private final SessionScopeCache scopeCache;

    public SessionController(CurrentUser currentUser,
                             SessionService sessionService,
                             ProductRepository productRepository,
                             SessionScopeCache scopeCache) {
        this.currentUser = currentUser;
        this.sessionService = sessionService;
        this.productRepository = productRepository;
        this.scopeCache = scopeCache;
    }

    @PostMapping("/start")
    public ApiResult<SessionScope> start(@Valid @RequestBody StartSessionRequest request) {
        Long userId = currentUser.id();
        SessionScope scope = resolveScope(userId, request);

        // Persist session row idempotently. SessionService treats the first
        // merchantId in scope as the canonical merchant column for the row;
        // for platform-wide scopes we pass null (matches existing schema).
        Long sessionMerchantId = scope.isPlatformWide() ? null : scope.allowedMerchantIds().getFirst();
        sessionService.getOrCreate(request.sessionId(), sessionMerchantId, userId, request.channel().name());

        scopeCache.put(request.sessionId(), scope);
        log.info("Session started: sessionId={}, channel={}, scope={}",
                request.sessionId(), request.channel(), scope);
        return ApiResult.ok(scope);
    }

    private SessionScope resolveScope(Long userId, StartSessionRequest request) {
        Channel channel = request.channel();
        return switch (channel) {
            case HOME_ENTRY, SEARCH_FALLBACK -> SessionScope.platformWide(userId);
            case PRODUCT_PAGE -> {
                if (request.boundProductId() == null) {
                    throw new IllegalArgumentException("PRODUCT_PAGE 必须携带 boundProductId");
                }
                Product product = productRepository.findById(request.boundProductId())
                        .orElseThrow(() -> new NotFoundException("商品不存在: " + request.boundProductId()));
                yield new SessionScope(userId, List.of(product.getMerchantId()), product.getId());
            }
            case MERCHANT_HOME -> {
                if (request.merchantId() == null) {
                    throw new IllegalArgumentException("MERCHANT_HOME 必须携带 merchantId");
                }
                yield new SessionScope(userId, List.of(request.merchantId()), null);
            }
        };
    }
}
