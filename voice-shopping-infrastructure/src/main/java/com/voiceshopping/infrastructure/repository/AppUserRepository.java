package com.voiceshopping.infrastructure.repository;

import com.voiceshopping.infrastructure.repository.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Basic CRUD repository for {@link AppUser}.
 * <p>
 * The login path uses {@link #findAllByPhoneAndDeletedAtIsNull} to enforce
 * <b>system-wide</b> phone uniqueness at the service layer (no DB unique
 * constraint to keep V6 migration minimal). If 2+ rows match the same phone,
 * the caller MUST fail-fast rather than silently picking the first.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByPhoneAndDeletedAtIsNull(String phone);

    List<AppUser> findAllByPhoneAndDeletedAtIsNull(String phone);
}
