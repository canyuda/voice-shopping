package com.voiceshopping.web.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.voiceshopping.infrastructure.repository.AppUserRepository;
import com.voiceshopping.infrastructure.repository.entity.AppUser;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sa-Token wrapper exposing the current request's logged-in user.
 * <p>
 * {@link #id()} delegates to {@link StpUtil#getLoginIdAsLong()} and therefore
 * relies on Sa-Token's per-thread context — call only from request-handling
 * threads (Controllers, intercepted services).
 */
@Component
public class CurrentUser {

    private final AppUserRepository appUserRepository;

    public CurrentUser(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * @return the userId stored on the current Sa-Token session
     * @throws cn.dev33.satoken.exception.NotLoginException if not logged in
     */
    public Long id() {
        return StpUtil.getLoginIdAsLong();
    }

    public boolean isLogin() {
        return StpUtil.isLogin();
    }

    /**
     * Whether the current user belongs to the given merchant.
     * <p>
     * <b>Merchant-operator scenarios only.</b> Do NOT call this from end-user
     * voice-shopping paths — those carry merchant scope on the
     * {@link com.voiceshopping.common.dto.session.SessionScope} record instead.
     */
    public boolean belongsToMerchant(Long merchantId) {
        if (merchantId == null) {
            return false;
        }
        Optional<AppUser> user = appUserRepository.findById(id());
        return user.map(AppUser::getMerchantId)
                .map(merchantId::equals)
                .orElse(false);
    }
}
