-- One row per product. available + reserved = stock on hand.
-- CHECK constraints are the last line of defence against overselling:
-- even if application code has a bug, PostgreSQL refuses negative stock.
CREATE TABLE inventory_items (
                                 id                 BIGSERIAL PRIMARY KEY,
                                 product_id         BIGINT    NOT NULL,
                                 available_quantity INTEGER   NOT NULL DEFAULT 0,
                                 reserved_quantity  INTEGER   NOT NULL DEFAULT 0,
                                 reorder_level      INTEGER   NOT NULL DEFAULT 0,
                                 created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 CONSTRAINT uq_inventory_product UNIQUE (product_id),
                                 CONSTRAINT ck_inventory_available_non_negative CHECK (available_quantity >= 0),
                                 CONSTRAINT ck_inventory_reserved_non_negative  CHECK (reserved_quantity  >= 0),
                                 CONSTRAINT ck_inventory_reorder_non_negative   CHECK (reorder_level      >= 0)
);

CREATE INDEX idx_inventory_low_stock ON inventory_items (available_quantity, reorder_level);