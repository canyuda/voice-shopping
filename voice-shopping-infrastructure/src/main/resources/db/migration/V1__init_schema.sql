-- ============================================================
-- Voice Shopping — Initial Schema
-- All tables, indexes, and triggers for the first release.
-- ============================================================

-- ============================================================
-- Extensions
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================
-- Shared: updated_at auto-update trigger function
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- merchant
-- ============================================================
CREATE TABLE merchant (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(200)    NOT NULL,
    contact_email   VARCHAR(320),
    contact_phone   VARCHAR(20),
    scale_level     VARCHAR(8)      NOT NULL DEFAULT 'NEW'
                                    CHECK (scale_level IN ('HEAD', 'MID', 'SMB', 'NEW')),
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    settings        JSONB           NOT NULL DEFAULT '{}',
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE merchant IS 'Multi-tenant merchant organizations';
COMMENT ON COLUMN merchant.scale_level IS 'HEAD=large enterprise, MID=mid-market, SMB=small business, NEW=newly onboarded';
COMMENT ON COLUMN merchant.settings IS 'Per-merchant config: LLM preferences, feature flags, branding';

CREATE INDEX idx_merchant_status ON merchant (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_merchant_scale_level ON merchant (scale_level) WHERE deleted_at IS NULL;

-- ============================================================
-- app_user
-- ============================================================
CREATE TABLE app_user (
    id              BIGSERIAL       PRIMARY KEY,
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    external_id     VARCHAR(100),
    nickname        VARCHAR(100),
    phone           VARCHAR(20),
    avatar_url      VARCHAR(500),
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED')),
    last_active_at  TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE app_user IS 'End users of the voice shopping service (avoids PG reserved keyword "user")';
COMMENT ON COLUMN app_user.external_id IS 'User identifier from merchant external system (WeChat openId, etc.)';

CREATE INDEX idx_app_user_merchant_id ON app_user (merchant_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_app_user_merchant_external ON app_user (merchant_id, external_id)
    WHERE external_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_app_user_phone ON app_user (merchant_id, phone) WHERE phone IS NOT NULL;

-- ============================================================
-- product
-- ============================================================
CREATE TABLE product (
    id              BIGSERIAL       PRIMARY KEY,
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    sku_code        VARCHAR(64),
    name            VARCHAR(500)    NOT NULL,
    category_l1     VARCHAR(100)    NOT NULL,
    category_l2     VARCHAR(100),
    is_new_arrival  BOOLEAN         NOT NULL DEFAULT false,
    description     TEXT,
    selling_points  TEXT,
    price           NUMERIC(12, 2)  NOT NULL CHECK (price >= 0),
    original_price  NUMERIC(12, 2)  CHECK (original_price >= 0),
    image_urls      JSONB           NOT NULL DEFAULT '[]',
    attributes      JSONB           NOT NULL DEFAULT '{}',
    status          VARCHAR(16)     NOT NULL DEFAULT 'ON_SALE'
                                    CHECK (status IN ('ON_SALE', 'OFF_SHELF', 'SOLD_OUT')),
    embedding       vector(1024),
    embedding_text  TEXT,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE product IS 'Product catalog with JSONB attributes and pgvector semantic embedding';
COMMENT ON COLUMN product.attributes IS 'Flexible attributes: {"category":"跑鞋","brand":"Asics","specs":{"weight":"280g","cushion":"high"}}';
COMMENT ON COLUMN product.embedding IS 'text-embedding-v3 1024-dim vector for semantic search';
COMMENT ON COLUMN product.description IS 'Objective product specifications (factual)';
COMMENT ON COLUMN product.selling_points IS 'Merchant marketing copy, given higher weight in embedding generation';
COMMENT ON COLUMN product.embedding_text IS 'Source text used for embedding, useful for re-embedding';

-- Multi-tenant isolation
CREATE INDEX idx_product_merchant_id ON product (merchant_id) WHERE deleted_at IS NULL;
-- GIN index for JSONB @> (contains) operator
CREATE INDEX idx_product_attributes_gin ON product USING gin (attributes jsonb_path_ops)
    WHERE deleted_at IS NULL;
-- HNSW index for vector cosine similarity search
CREATE INDEX idx_product_embedding_hnsw ON product
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64)
    WHERE embedding IS NOT NULL AND deleted_at IS NULL;
-- Composite index for common filter: status + price range
CREATE INDEX idx_product_status_price ON product (merchant_id, status, price) WHERE deleted_at IS NULL;
-- Category filtering for recommendation and search
CREATE INDEX idx_product_category ON product (merchant_id, category_l1, category_l2) WHERE deleted_at IS NULL;
-- SKU code lookup
CREATE INDEX idx_product_sku_code ON product (merchant_id, sku_code) WHERE sku_code IS NOT NULL AND deleted_at IS NULL;
-- Trigram index for fuzzy name matching (typo-tolerant, keyword fallback)
CREATE INDEX idx_product_name_trgm ON product USING gin (name gin_trgm_ops) WHERE deleted_at IS NULL;

CREATE TRIGGER trg_product_updated_at
    BEFORE UPDATE ON product FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- faq_entry
-- ============================================================
CREATE TABLE faq_entry (
    id              BIGSERIAL       PRIMARY KEY,
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    question        TEXT            NOT NULL,
    answer          TEXT            NOT NULL,
    category        VARCHAR(100),
    tags            JSONB           NOT NULL DEFAULT '[]',
    frequency       INT             NOT NULL DEFAULT 0,
    embedding       vector(1024),
    embedding_text  TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE faq_entry IS 'FAQ knowledge base for non-shopping high-frequency questions';
COMMENT ON COLUMN faq_entry.merchant_id IS '0=platform-wide FAQ (shipping, returns), >0=merchant-specific. Query with merchant_id IN (0, :currentMerchant)';
COMMENT ON COLUMN faq_entry.frequency IS 'Match count for popularity ranking';

CREATE INDEX idx_faq_merchant_id ON faq_entry (merchant_id);
CREATE INDEX idx_faq_category ON faq_entry (merchant_id, category);
CREATE INDEX idx_faq_tags_gin ON faq_entry USING gin (tags jsonb_path_ops);
CREATE INDEX idx_faq_embedding_hnsw ON faq_entry
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64)
    WHERE embedding IS NOT NULL;
CREATE INDEX idx_faq_frequency ON faq_entry (merchant_id, frequency DESC);

CREATE TRIGGER trg_faq_updated_at
    BEFORE UPDATE ON faq_entry FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- user_profile_static
-- ============================================================
CREATE TABLE user_profile_static (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL UNIQUE REFERENCES app_user(id),
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    gender          VARCHAR(10)     CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNSPECIFIED')),
    age_range       VARCHAR(10)     CHECK (age_range IN ('18-24', '25-34', '35-44', '45-54', '55-64', '65+')),
    city            VARCHAR(100),
    body_height     NUMERIC(5, 1)   CHECK (body_height > 0 AND body_height < 300),
    body_weight     NUMERIC(5, 1)   CHECK (body_weight > 0 AND body_weight < 500),
    shoe_size       VARCHAR(20),
    clothing_size   VARCHAR(20),
    skin_type       VARCHAR(16)     CHECK (skin_type IN ('OILY', 'DRY', 'COMBINATION', 'SENSITIVE', 'NORMAL', 'UNSPECIFIED')),
    tech_familiarity VARCHAR(10)    CHECK (tech_familiarity IN ('LOW', 'MEDIUM', 'HIGH')),
    spending_range  VARCHAR(20)     CHECK (spending_range IN ('0-100', '100-300', '300-500', '500-1000', '1000+')),
    extra           JSONB           NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_profile_static IS 'Static user profile: demographics and body measurements';

CREATE INDEX idx_user_profile_static_user ON user_profile_static (user_id);
CREATE INDEX idx_user_profile_static_merchant ON user_profile_static (merchant_id);

CREATE TRIGGER trg_user_profile_static_updated_at
    BEFORE UPDATE ON user_profile_static FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- user_profile_dynamic
-- ============================================================
CREATE TABLE user_profile_dynamic (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL UNIQUE REFERENCES app_user(id),
    merchant_id       BIGINT          NOT NULL REFERENCES merchant(id),
    category_prefs    JSONB           NOT NULL DEFAULT '{}',
    brand_prefs       JSONB           NOT NULL DEFAULT '{}',
    price_sensitivity VARCHAR(10)     DEFAULT 'MEDIUM'
                                      CHECK (price_sensitivity IN ('LOW', 'MEDIUM', 'HIGH')),
    recent_behavior   JSONB           NOT NULL DEFAULT '[]',
    purchase_count    INT             NOT NULL DEFAULT 0,
    avg_order_amount  NUMERIC(12, 2)  CHECK (avg_order_amount >= 0),
    last_purchase_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_profile_dynamic IS 'Dynamic user profile: preferences, behavior, price sensitivity';
COMMENT ON COLUMN user_profile_dynamic.category_prefs IS 'Category preference scores: {"跑鞋":0.9,"篮球鞋":0.7}';
COMMENT ON COLUMN user_profile_dynamic.brand_prefs IS 'Brand preference scores: {"Nike":0.8,"Asics":0.9}';
COMMENT ON COLUMN user_profile_dynamic.recent_behavior IS 'Recent events: [{"action":"view","productId":8821,"ts":"2026-06-11T10:00:00Z"}]';

CREATE INDEX idx_user_profile_dynamic_user ON user_profile_dynamic (user_id);
CREATE INDEX idx_user_profile_dynamic_merchant ON user_profile_dynamic (merchant_id);
CREATE INDEX idx_user_profile_dynamic_cat_prefs ON user_profile_dynamic USING gin (category_prefs);
CREATE INDEX idx_user_profile_dynamic_last_active ON user_profile_dynamic (merchant_id, last_purchase_at DESC);

CREATE TRIGGER trg_user_profile_dynamic_updated_at
    BEFORE UPDATE ON user_profile_dynamic FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- session
-- ============================================================
CREATE TABLE session (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     BIGINT          REFERENCES merchant(id),
    user_id         BIGINT          NOT NULL REFERENCES app_user(id),
    channel         VARCHAR(25)     NOT NULL DEFAULT 'HOME_ENTRY'
                                    CHECK (channel IN ('HOME_ENTRY', 'PRODUCT_PAGE', 'SEARCH_FALLBACK')),
    outcome         VARCHAR(20)     CHECK (outcome IN (
                                        'RECOMMENDATION', 'ORDER', 'FAQ_ANSWERED',
                                        'CHITCHAT', 'FOLLOWUP', 'ABANDONED', 'ERROR'
                                    )),
    total_tokens    INT             NOT NULL DEFAULT 0,
    bound_product_id BIGINT         REFERENCES product(id),
    started_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE session IS 'Voice shopping session metadata';
COMMENT ON COLUMN session.channel IS 'Entry point: HOME_ENTRY=homepage, PRODUCT_PAGE=product detail, SEARCH_FALLBACK=search keyword fallback';
COMMENT ON COLUMN session.total_tokens IS 'Total LLM tokens consumed in this session for cost tracking';
COMMENT ON COLUMN session.bound_product_id IS 'Product context when session started from product page (PRODUCT_PAGE channel)';

CREATE INDEX idx_session_merchant_user ON session (merchant_id, user_id);
CREATE INDEX idx_session_started_at ON session (merchant_id, started_at DESC);
CREATE INDEX idx_session_outcome ON session (outcome) WHERE outcome IS NOT NULL;

CREATE TRIGGER trg_session_updated_at
    BEFORE UPDATE ON session FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- session_message
-- ============================================================
CREATE TABLE session_message (
    id              BIGSERIAL       PRIMARY KEY,
    session_id      UUID            NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    role            VARCHAR(16)     NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    turn            INT             NOT NULL,
    agent_name      VARCHAR(32),
    content         TEXT            NOT NULL,
    content_audio_url VARCHAR(500),
    intent          VARCHAR(32),
    tokens          INT             NOT NULL DEFAULT 0,
    metadata        JSONB           NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE session_message IS 'Session dialogue history (append-only)';
COMMENT ON COLUMN session_message.metadata IS '{"asrConfidence":0.95,"ttsVoice":"cosyvoice-v1"}';

CREATE INDEX idx_session_message_session_id ON session_message (session_id, created_at);
CREATE INDEX idx_session_message_merchant ON session_message (merchant_id);

-- ============================================================
-- session_state
-- ============================================================
CREATE TABLE session_state (
    id              UUID            PRIMARY KEY REFERENCES session(id) ON DELETE CASCADE,
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    phase           VARCHAR(32)     NOT NULL DEFAULT 'IDLE'
                                    CHECK (phase IN (
                                        'IDLE', 'INTENT_PARSED', 'CLARIFYING',
                                        'READY_TO_RECOMMEND', 'GENERATING_SPEECH',
                                        'ORDER_CONFIRMING'
                                    )),
    current_intent  VARCHAR(32),
    slots           JSONB           NOT NULL DEFAULT '{}',
    pending_ask     TEXT,
    turn_count      INT             NOT NULL DEFAULT 0,
    last_recommendations JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE session_state IS 'Orchestrator state machine per session (DB source of truth, Redis for hot cache)';
COMMENT ON COLUMN session_state.slots IS 'Accumulated slots: {"category":"跑鞋","budget":500,"scenario":"水泥路"}';
COMMENT ON COLUMN session_state.pending_ask IS 'ClarifyAgent pending question when action=ASK';
COMMENT ON COLUMN session_state.last_recommendations IS 'Cached RecAgent recommendations for current turn';

CREATE INDEX idx_session_state_merchant ON session_state (merchant_id);
CREATE INDEX idx_session_state_phase ON session_state (phase);

CREATE TRIGGER trg_session_state_updated_at
    BEFORE UPDATE ON session_state FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- order_record
-- ============================================================
CREATE TABLE order_record (
    id              BIGSERIAL       PRIMARY KEY,
    merchant_id     BIGINT          NOT NULL REFERENCES merchant(id),
    user_id         BIGINT          NOT NULL REFERENCES app_user(id),
    session_id      UUID            REFERENCES session(id),
    order_no        VARCHAR(64)     NOT NULL,
    items           JSONB           NOT NULL,
    total_amount    NUMERIC(12, 2)  NOT NULL CHECK (total_amount >= 0),
    status          VARCHAR(16)     NOT NULL DEFAULT 'CREATED'
                                    CHECK (status IN ('CREATED', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED')),
    agent_attribution BOOLEAN      NOT NULL DEFAULT false,
    source_intent   VARCHAR(32),
    ai_context      JSONB,
    receiver_name   VARCHAR(100),
    receiver_phone  VARCHAR(20),
    receiver_addr   TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE order_record IS 'Order records with AI attribution markers';
COMMENT ON COLUMN order_record.items IS 'Order line items: [{"productId":8821,"name":"Asics GEL-Contend 9","price":479,"quantity":1}]';
COMMENT ON COLUMN order_record.agent_attribution IS 'true=AI recommendation led to this order, for ROI attribution analysis';
COMMENT ON COLUMN order_record.source_intent IS 'Intent that triggered the order: PRODUCT_RECOMMENDATION, PRODUCT_COMPARE, etc.';
COMMENT ON COLUMN order_record.ai_context IS 'AI decision context: {"recAgentScore":0.88,"clarifyRounds":2,"slots":{...}}';
COMMENT ON COLUMN order_record.receiver_name IS 'Recipient name (plaintext for demo, apply privacy rules per business policy)';
COMMENT ON COLUMN order_record.receiver_phone IS 'Recipient phone (plaintext for demo, apply privacy rules per business policy)';
COMMENT ON COLUMN order_record.receiver_addr IS 'Recipient address (plaintext for demo, apply privacy rules per business policy)';

CREATE UNIQUE INDEX uk_order_record_order_no ON order_record (merchant_id, order_no);
CREATE INDEX idx_order_record_merchant_user ON order_record (merchant_id, user_id);
CREATE INDEX idx_order_record_user_created ON order_record (user_id, created_at DESC);
CREATE INDEX idx_order_record_session_id ON order_record (session_id) WHERE session_id IS NOT NULL;
CREATE INDEX idx_order_record_status ON order_record (merchant_id, status);
CREATE INDEX idx_order_record_created_at ON order_record (merchant_id, created_at DESC);

CREATE TRIGGER trg_order_record_updated_at
    BEFORE UPDATE ON order_record FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
