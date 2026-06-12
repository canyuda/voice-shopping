package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.SessionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for {@link SessionState}.
 */
public interface SessionStateRepository extends JpaRepository<SessionState, UUID> {
}
