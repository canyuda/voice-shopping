package com.voiceshopping.infrastructure.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
 * JPA entity for the {@code product} table.
 * <p>
 * The {@code embedding} (vector(1024)) field is declared as {@code @Transient} —
 * all vector read/write operations go through JdbcTemplate + native SQL.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "sku_code")
    private String skuCode;

    @Column(nullable = false)
    private String name;

    @Column(name = "category_l1", nullable = false)
    private String categoryL1;

    @Column(name = "category_l2")
    private String categoryL2;

    @Column(name = "is_new_arrival", nullable = false)
    private Boolean newArrival = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "selling_points", columnDefinition = "TEXT")
    private String sellingPoints;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "original_price")
    private BigDecimal originalPrice;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls", columnDefinition = "jsonb", nullable = false)
    private List<String> imageUrls = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> attributes = new HashMap<>();

    @Column(nullable = false)
    private String status = "ON_SALE";

    /**
     * On-hand inventory. Decremented atomically by
     * {@link com.voiceshopping.infrastructure.repository.ProductRepository#decrementStock}
     * — never mutate via setStock from business code on the order path.
     */
    @Column(nullable = false)
    private Integer stock = 0;

    /**
     * Declared as Transient — vector operations handled by JdbcTemplate.
     * Column exists in DB as vector(1024) but JPA does not map it.
     */
    @Transient
    private float[] embedding;

    @Column(name = "embedding_text", columnDefinition = "TEXT")
    private String embeddingText;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategoryL1() { return categoryL1; }
    public void setCategoryL1(String categoryL1) { this.categoryL1 = categoryL1; }

    public String getCategoryL2() { return categoryL2; }
    public void setCategoryL2(String categoryL2) { this.categoryL2 = categoryL2; }

    public Boolean getNewArrival() { return newArrival; }
    public void setNewArrival(Boolean newArrival) { this.newArrival = newArrival; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSellingPoints() { return sellingPoints; }
    public void setSellingPoints(String sellingPoints) { this.sellingPoints = sellingPoints; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public String getEmbeddingText() { return embeddingText; }
    public void setEmbeddingText(String embeddingText) { this.embeddingText = embeddingText; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
