package com.voiceshopping.web.auth;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.voiceshopping.infrastructure.repository.AppUserRepository;
import com.voiceshopping.infrastructure.repository.entity.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Covers the four CurrentUser scenarios in merchant-data-isolation spec:
 * id() / NotLoginException / belongsToMerchant hit / miss.
 * <p>
 * Sa-Token's static {@code StpUtil} relies on a Spring-managed context that
 * isn't bootstrapped in unit tests. We stub the static surface with
 * {@link MockedStatic} to keep the test focused on {@link CurrentUser}'s
 * own logic.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserTest {

    @Mock
    private AppUserRepository appUserRepository;

    private CurrentUser currentUser;
    private MockedStatic<StpUtil> stpUtilMock;

    @BeforeEach
    void setUp() {
        currentUser = new CurrentUser(appUserRepository);
        stpUtilMock = Mockito.mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stpUtilMock.close();
    }

    @Test
    void id_returnsLoginIdWhenLoggedIn() {
        stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(123L);

        assertTrue(currentUser.isLogin());
        assertEquals(123L, currentUser.id());
    }

    @Test
    void id_throwsNotLoginExceptionWhenNotLoggedIn() {
        stpUtilMock.when(StpUtil::isLogin).thenReturn(false);
        stpUtilMock.when(StpUtil::getLoginIdAsLong)
                .thenThrow(NotLoginException.newInstance("login", "NOT_TOKEN", "未登录", null));

        assertFalse(currentUser.isLogin());
        assertThrows(NotLoginException.class, () -> currentUser.id());
    }

    @Test
    void belongsToMerchant_trueWhenMerchantIdMatches() {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setMerchantId(5L);
        when(appUserRepository.findById(7L)).thenReturn(Optional.of(user));
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

        assertTrue(currentUser.belongsToMerchant(5L));
    }

    @Test
    void belongsToMerchant_falseWhenMerchantIdDiffers() {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setMerchantId(5L);
        when(appUserRepository.findById(7L)).thenReturn(Optional.of(user));
        stpUtilMock.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

        assertFalse(currentUser.belongsToMerchant(9L));
    }
}
