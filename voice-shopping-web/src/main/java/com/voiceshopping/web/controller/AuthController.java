package com.voiceshopping.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.exception.NotFoundException;
import com.voiceshopping.infrastructure.repository.AppUserRepository;
import com.voiceshopping.infrastructure.repository.entity.AppUser;
import com.voiceshopping.web.dto.LoginRequest;
import com.voiceshopping.web.dto.LoginResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Authentication endpoints. Phase-1 scope: phone-based login only — no
 * password / OTP / OAuth check (see merchant-data-isolation Decision 1).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository appUserRepository;

    public AuthController(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        List<AppUser> matches = appUserRepository.findAllByPhoneAndDeletedAtIsNull(request.phone());

        if (matches.isEmpty()) {
            throw new NotFoundException("用户不存在: phone=" + request.phone());
        }
        if (matches.size() > 1) {
            // Phone uniqueness is asserted at the service layer (no DB unique
            // constraint to keep V6 minimal). Surfacing all hits at ERROR level
            // makes the data fix obvious — never silently pick first.
            List<Long> ids = matches.stream().map(AppUser::getId).toList();
            log.error("phone 不唯一，登录拒绝: phone={}, hits={}", request.phone(), ids);
            throw new IllegalStateException("phone 不唯一: " + request.phone());
        }

        AppUser user = matches.getFirst();
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        log.info("Login succeeded: userId={}, phone={}", user.getId(), request.phone());
        return ApiResult.ok(new LoginResponse(token));
    }
}
