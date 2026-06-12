package com.voiceshopping.business.behavior;

import org.springframework.context.ApplicationEvent;

/**
 * Event fired when a user views a product.
 */
public class UserViewedEvent extends ApplicationEvent {

    private final long userId;
    private final String category;
    private final String brand;

    public UserViewedEvent(Object source, long userId, String category, String brand) {
        super(source);
        this.userId = userId;
        this.category = category;
        this.brand = brand;
    }

    public long getUserId() { return userId; }
    public String getCategory() { return category; }
    public String getBrand() { return brand; }
}
