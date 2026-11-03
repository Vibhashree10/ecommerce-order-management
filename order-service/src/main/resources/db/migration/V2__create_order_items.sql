-- Lines of an order. sku / product_name / unit_price are a SNAPSHOT taken when the order was placed,
-- so later price or name changes in product-service never change an existing order.
CREATE TABLE order_items (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    sku          VARCHAR(64),
    product_name VARCHAR(255),
    unit_price   NUMERIC(12, 2) NOT NULL,
    quantity     INTEGER        NOT NULL,
    line_total   NUMERIC(12, 2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_items_price_non_negative CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
