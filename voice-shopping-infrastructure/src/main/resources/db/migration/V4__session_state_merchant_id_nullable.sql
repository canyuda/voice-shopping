-- ============================================================
-- V4: Drop NOT NULL on session_state.merchant_id.
-- The parent session.merchant_id has been nullable since V1, so
-- this aligns the child column with the parent. Multi-tenant
-- isolation is enforced at application level (V2 dropped all FKs).
-- ============================================================

ALTER TABLE session_state ALTER COLUMN merchant_id DROP NOT NULL;
