-- ============================================================
-- V6: Add MERCHANT_HOME to session.channel CHECK constraint
--
-- Adds a new entry-channel value used by the multi-tenant data
-- isolation feature. The existing constraint set
-- (HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK) is preserved.
--
-- Rollback note: only safe if no row exists with channel='MERCHANT_HOME';
-- otherwise the re-tightened CHECK would fail at validation.
-- ============================================================

ALTER TABLE session DROP CONSTRAINT session_channel_check;

ALTER TABLE session ADD CONSTRAINT session_channel_check
    CHECK (channel IN ('HOME_ENTRY', 'PRODUCT_PAGE', 'MERCHANT_HOME', 'SEARCH_FALLBACK'));
