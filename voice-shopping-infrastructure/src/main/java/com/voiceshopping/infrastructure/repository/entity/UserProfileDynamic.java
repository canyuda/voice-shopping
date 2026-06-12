package com.voiceshopping.infrastructure.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA entity for the {@code user_profile_dynamic} table.
 * Dynamic user profile: preferences, behavior, price sensitivity.
 */
@Entity
@Table(name = "user_profile_dynamic")
public class UserProfileDynamic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_prefs", columnDefinition = "jsonb", nullable = false)
    private Map<String, Double> categoryPrefs = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brand_prefs", columnDefinition = "jsonb", nullable = false)
    private Map<String, Double> brandPrefs = new HashMap<>();

    @Column(name = "price_sensitivity")
    private String priceSensitivity = "MEDIUM";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recent_behavior", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> recentBehavior = new ArrayList<>();

    @Column(name = "purchase_count", nullable = false)
    private Integer purchaseCount = 0;

    @Column(name = "avg_order_amount", precision = 12, scale = 2)
    private BigDecimal avgOrderAmount;

    @Column(name = "last_purchase_at")
    private Instant lastPurchaseAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Map<String, Double> getCategoryPrefs() { return categoryPrefs; }
    public void setCategoryPrefs(Map<String, Double> categoryPrefs) { this.categoryPrefs = categoryPrefs; }

    public Map<String, Double> getBrandPrefs() { return brandPrefs; }
    public void setBrandPrefs(Map<String, Double> brandPrefs) { this.brandPrefs = brandPrefs; }

    public String getPriceSensitivity() { return priceSensitivity; }
    public void setPriceSensitivity(String priceSensitivity) { this.priceSensitivity = priceSensitivity; }

    public List<Map<String, Object>> getRecentBehavior() { return recentBehavior; }
    public void setRecentBehavior(List<Map<String, Object>> recentBehavior) { this.recentBehavior = recentBehavior; }

    public Integer getPurchaseCount() { return purchaseCount; }
    public void setPurchaseCount(Integer purchaseCount) { this.purchaseCount = purchaseCount; }

    public BigDecimal getAvgOrderAmount() { return avgOrderAmount; }
    public void setAvgOrderAmount(BigDecimal avgOrderAmount) { this.avgOrderAmount = avgOrderAmount; }

    public Instant getLastPurchaseAt() { return lastPurchaseAt; }
    public void setLastPurchaseAt(Instant lastPurchaseAt) { this.lastPurchaseAt = lastPurchaseAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
