package com.voiceshopping.business.behavior;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * Event fired when a user purchases a product.
 * <p>
 * Carries an optional {@code sessionId} so that listeners interested in the
 * conversational context (e.g. {@code LongTermMemoryWriter#flushOnSessionEnd})
 * can locate the originating session. Legacy / back-office paths that fire
 * this event without a conversation MUST pass {@code null}.
 */
public class UserPurchasedEvent extends ApplicationEvent {

    private final long userId;
    private final String category;
    private final String brand;
    private final BigDecimal amount;
    private final String sessionId;

    public UserPurchasedEvent(Object source,
                              long userId,
                              String category,
                              String brand,
                              BigDecimal amount,
                              String sessionId) {
        super(source);
        this.userId = userId;
        this.category = category;
        this.brand = brand;
        this.amount = amount;
        this.sessionId = sessionId;
    }

    /**
     * Backwards-compatible constructor for callers that don't have a session
     * context (e.g. admin / batch back-office order import). Equivalent to
     * passing {@code sessionId = null}.
     */
    public UserPurchasedEvent(Object source,
                              long userId,
                              String category,
                              String brand,
                              BigDecimal amount) {
        this(source, userId, category, brand, amount, null);
    }

    public long getUserId() { return userId; }
    public String getCategory() { return category; }
    public String getBrand() { return brand; }
    public BigDecimal getAmount() { return amount; }
    public String getSessionId() { return sessionId; }
}
