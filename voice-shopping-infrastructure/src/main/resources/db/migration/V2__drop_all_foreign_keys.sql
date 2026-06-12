-- ============================================================
-- V2: Drop all foreign key constraints
-- Multi-tenant isolation is enforced at application level
-- (MyBatis-Plus tenant plugin + Sa-Token row-level filtering).
-- Removing FK constraints eliminates lock contention during
-- batch inserts and cross-tenant admin operations.
-- ============================================================

-- app_user
ALTER TABLE app_user       DROP CONSTRAINT app_user_merchant_id_fkey;

-- product
ALTER TABLE product        DROP CONSTRAINT product_merchant_id_fkey;

-- faq_entry
ALTER TABLE faq_entry      DROP CONSTRAINT faq_entry_merchant_id_fkey;

-- user_profile_static
ALTER TABLE user_profile_static
    DROP CONSTRAINT user_profile_static_user_id_fkey,
    DROP CONSTRAINT user_profile_static_merchant_id_fkey;

-- user_profile_dynamic
ALTER TABLE user_profile_dynamic
    DROP CONSTRAINT user_profile_dynamic_user_id_fkey,
    DROP CONSTRAINT user_profile_dynamic_merchant_id_fkey;

-- session
ALTER TABLE session
    DROP CONSTRAINT session_merchant_id_fkey,
    DROP CONSTRAINT session_user_id_fkey,
    DROP CONSTRAINT session_bound_product_id_fkey;

-- session_message
ALTER TABLE session_message
    DROP CONSTRAINT session_message_session_id_fkey,
    DROP CONSTRAINT session_message_merchant_id_fkey;

-- session_state
ALTER TABLE session_state
    DROP CONSTRAINT session_state_id_fkey,
    DROP CONSTRAINT session_state_merchant_id_fkey;

-- order_record
ALTER TABLE order_record
    DROP CONSTRAINT order_record_merchant_id_fkey,
    DROP CONSTRAINT order_record_user_id_fkey,
    DROP CONSTRAINT order_record_session_id_fkey;
