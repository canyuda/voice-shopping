package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link Session}.
 */
public interface SessionRepository extends JpaRepository<Session, String> {

    List<Session> findByUserIdOrderByStartedAtDesc(Long userId);
}
