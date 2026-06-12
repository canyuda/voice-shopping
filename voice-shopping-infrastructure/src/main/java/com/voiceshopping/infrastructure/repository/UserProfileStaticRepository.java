package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.UserProfileStatic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link UserProfileStatic}.
 */
public interface UserProfileStaticRepository extends JpaRepository<UserProfileStatic, Long> {

    Optional<UserProfileStatic> findByUserId(Long userId);
}
