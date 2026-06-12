package com.voiceshopping.infrastructure.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the {@code session_state} table.
 * Orchestrator state machine per session (DB source of truth, Redis for hot cache).
 */
@Entity
@Table(name = "session_state")
public class SessionState {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private String phase = "IDLE";

    @Column(name = "current_intent")
    private String currentIntent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> slots = new HashMap<>();

    @Column(name = "pending_ask", columnDefinition = "TEXT")
    private String pendingAsk;

    @Column(name = "turn_count", nullable = false)
    private Integer turnCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_recommendations", columnDefinition = "jsonb")
    private Map<String, Object> lastRecommendations;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    public String getCurrentIntent() { return currentIntent; }
    public void setCurrentIntent(String currentIntent) { this.currentIntent = currentIntent; }

    public Map<String, Object> getSlots() { return slots; }
    public void setSlots(Map<String, Object> slots) { this.slots = slots; }

    public String getPendingAsk() { return pendingAsk; }
    public void setPendingAsk(String pendingAsk) { this.pendingAsk = pendingAsk; }

    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

    public Map<String, Object> getLastRecommendations() { return lastRecommendations; }
    public void setLastRecommendations(Map<String, Object> lastRecommendations) { this.lastRecommendations = lastRecommendations; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
