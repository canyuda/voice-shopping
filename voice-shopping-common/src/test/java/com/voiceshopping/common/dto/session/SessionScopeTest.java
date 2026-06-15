package com.voiceshopping.common.dto.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionScopeTest {

    @Test
    void isPlatformWide_trueWhenAllowedMerchantIdsIsNull() {
        assertTrue(new SessionScope(7L, null, null).isPlatformWide());
    }

    @Test
    void isPlatformWide_trueWhenAllowedMerchantIdsIsEmpty() {
        assertTrue(new SessionScope(7L, List.of(), null).isPlatformWide());
    }

    @Test
    void isPlatformWide_falseWhenAllowedMerchantIdsHasEntries() {
        assertFalse(new SessionScope(7L, List.of(5L), null).isPlatformWide());
    }

    @Test
    void platformWide_factoryReturnsConsistentScope() {
        SessionScope scope = SessionScope.platformWide(42L);
        assertEquals(42L, scope.userId());
        assertEquals(List.of(), scope.allowedMerchantIds());
        assertTrue(scope.isPlatformWide());
    }
}
