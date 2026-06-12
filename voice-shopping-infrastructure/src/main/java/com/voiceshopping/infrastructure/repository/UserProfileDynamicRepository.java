package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.UserProfileDynamic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link UserProfileDynamic}.
 */
public interface UserProfileDynamicRepository extends JpaRepository<UserProfileDynamic, Long> {

    Optional<UserProfileDynamic> findByUserId(Long userId);
}
