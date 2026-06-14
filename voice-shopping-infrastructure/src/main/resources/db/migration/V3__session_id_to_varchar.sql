-- ============================================================
-- V3: Migrate session id columns from UUID to VARCHAR(64).
-- Allows clients to bring their own opaque session identifiers
-- (e.g. "sess-xyz") instead of being forced to generate UUIDs.
--
-- Affected columns:
--   session.id              UUID PK -> VARCHAR(64) PK
--   session_message.session_id  UUID -> VARCHAR(64)
--   session_state.id        UUID PK -> VARCHAR(64) PK
--   order_record.session_id UUID -> VARCHAR(64)
--
-- The DB-side default (gen_random_uuid()) is dropped — the application
-- layer is now responsible for supplying a sessionId on every insert.
-- ============================================================

ALTER TABLE session            ALTER COLUMN id           DROP DEFAULT;
ALTER TABLE session            ALTER COLUMN id           TYPE VARCHAR(64) USING id::text;

ALTER TABLE session_message    ALTER COLUMN session_id   TYPE VARCHAR(64) USING session_id::text;

ALTER TABLE session_state      ALTER COLUMN id           TYPE VARCHAR(64) USING id::text;

ALTER TABLE order_record       ALTER COLUMN session_id   TYPE VARCHAR(64) USING session_id::text;
