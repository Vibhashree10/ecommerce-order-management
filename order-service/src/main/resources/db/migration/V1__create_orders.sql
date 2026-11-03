-- One row per customer order. The state machine is:
--   PENDING -> STOCK_RESERVED -> CONFIRMED
--   PENDING -> REJECTED (no stock)         PENDING / STOCK_RESERVED -> CANCELLED
-- order_number is the public id AND the reservation id sent to inventory-service.
-- UNIQUE (customer_id, idempotency_key) makes "create order" safe to retry (NULL keys never collide).
CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    order_number    VARCHAR(64)    NOT NULL,
    customer_id     VARCHAR(100)   NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    total_amount    NUMERIC(12, 2) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    idempotency_key VARCHAR(100),
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT uq_orders_customer_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_orders_total_non_negative CHECK (total_amount >= 0),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'STOCK_RESERVED', 'CONFIRMED', 'CANCELLED', 'REJECTED'))
);

CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at);
CREATE INDEX idx_orders_status_updated ON orders (status, updated_at);
