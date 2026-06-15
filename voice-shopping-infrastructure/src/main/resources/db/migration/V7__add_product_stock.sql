-- ============================================================
-- V7: product.stock column for order placement flow
-- ============================================================
-- Original V1 schema lacked a stock column. The order-placement-flow
-- change requires atomic stock decrement via SQL — this migration adds
-- the column with a CHECK constraint preventing negative balances,
-- plus an index supporting "in-stock products for merchant" debug
-- queries on the admin/debug paths.

ALTER TABLE product
    ADD COLUMN stock INTEGER NOT NULL DEFAULT 0
        CHECK (stock >= 0);

COMMENT ON COLUMN product.stock IS 'On-hand inventory units; decremented atomically by OrderService.confirm via UPDATE ... WHERE stock >= :qty';

-- Partial index: only in-stock, on-sale products per merchant.
-- Sized small (stock > 0 filters out most rows), supports admin listing.
CREATE INDEX idx_product_merchant_in_stock
    ON product (merchant_id, status)
    WHERE deleted_at IS NULL AND stock > 0;
