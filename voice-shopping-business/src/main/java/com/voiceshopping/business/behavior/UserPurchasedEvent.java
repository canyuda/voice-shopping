package com.voiceshopping.business.behavior;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * Event fired when a user purchases a product.
 */
public class UserPurchasedEvent extends ApplicationEvent {

    private final long userId;
    private final String category;
    private final String brand;
    private final BigDecimal amount;

    public UserPurchasedEvent(Object source, long userId, String category, String brand, BigDecimal amount) {
        super(source);
        this.userId = userId;
        this.category = category;
        this.brand = brand;
        this.amount = amount;
    }

    public long getUserId() { return userId; }
    public String getCategory() { return category; }
    public String getBrand() { return brand; }
    public BigDecimal getAmount() { return amount; }
}
