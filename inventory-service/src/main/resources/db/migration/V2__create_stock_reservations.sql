-- A reservation holds stock for an order until it is confirmed, released or expires.
-- UNIQUE (order_id, product_id) makes "reserve" idempotent per order.
CREATE TABLE stock_reservations (
                                    id          BIGSERIAL PRIMARY KEY,
                                    order_id    VARCHAR(64) NOT NULL,
                                    product_id  BIGINT      NOT NULL,
                                    quantity    INTEGER     NOT NULL,
                                    status      VARCHAR(20) NOT NULL,
                                    expires_at  TIMESTAMP   NOT NULL,
                                    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    CONSTRAINT uq_reservation_order_product UNIQUE (order_id, product_id),
                                    CONSTRAINT ck_reservation_quantity_positive CHECK (quantity > 0),
                                    CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES inventory_items (product_id)
);

CREATE INDEX idx_reservations_order ON stock_reservations (order_id);
CREATE INDEX idx_reservations_status_expires ON stock_reservations (status, expires_at);