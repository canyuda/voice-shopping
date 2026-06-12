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
import java.util.HashMap;
import java.util.Map;

/**
 * JPA entity for the {@code user_profile_static} table.
 * Static user profile: demographics and body measurements.
 */
@Entity
@Table(name = "user_profile_static")
public class UserProfileStatic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column
    private String gender;

    @Column(name = "age_range")
    private String ageRange;

    @Column
    private String city;

    @Column(name = "body_height", precision = 5, scale = 1)
    private BigDecimal bodyHeight;

    @Column(name = "body_weight", precision = 5, scale = 1)
    private BigDecimal bodyWeight;

    @Column(name = "shoe_size")
    private String shoeSize;

    @Column(name = "clothing_size")
    private String clothingSize;

    @Column(name = "skin_type")
    private String skinType;

    @Column(name = "tech_familiarity")
    private String techFamiliarity;

    @Column(name = "spending_range")
    private String spendingRange;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

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

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getAgeRange() { return ageRange; }
    public void setAgeRange(String ageRange) { this.ageRange = ageRange; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public BigDecimal getBodyHeight() { return bodyHeight; }
    public void setBodyHeight(BigDecimal bodyHeight) { this.bodyHeight = bodyHeight; }

    public BigDecimal getBodyWeight() { return bodyWeight; }
    public void setBodyWeight(BigDecimal bodyWeight) { this.bodyWeight = bodyWeight; }

    public String getShoeSize() { return shoeSize; }
    public void setShoeSize(String shoeSize) { this.shoeSize = shoeSize; }

    public String getClothingSize() { return clothingSize; }
    public void setClothingSize(String clothingSize) { this.clothingSize = clothingSize; }

    public String getSkinType() { return skinType; }
    public void setSkinType(String skinType) { this.skinType = skinType; }

    public String getTechFamiliarity() { return techFamiliarity; }
    public void setTechFamiliarity(String techFamiliarity) { this.techFamiliarity = techFamiliarity; }

    public String getSpendingRange() { return spendingRange; }
    public void setSpendingRange(String spendingRange) { this.spendingRange = spendingRange; }

    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
