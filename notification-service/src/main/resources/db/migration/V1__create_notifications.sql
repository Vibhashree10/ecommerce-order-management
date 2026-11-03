-- One row per (event, channel). UNIQUE (dedupe_key, channel) makes event handling idempotent:
-- Kafka can deliver the same event twice (rebalance, retry) and the second delivery inserts nothing.
-- dedupe_key = '<eventType>:<orderNumber>' (an order has each lifecycle event at most once).
CREATE TABLE notifications (
    id            BIGSERIAL PRIMARY KEY,
    dedupe_key    VARCHAR(150)  NOT NULL,
    channel       VARCHAR(20)   NOT NULL,
    event_type    VARCHAR(50)   NOT NULL,
    order_number  VARCHAR(64)   NOT NULL,
    customer_id   VARCHAR(100),
    recipient     VARCHAR(255),
    subject       VARCHAR(255)  NOT NULL,
    body          VARCHAR(2000) NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    attempts      INT           NOT NULL DEFAULT 0,
    last_error    VARCHAR(255),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at       TIMESTAMP,
    CONSTRAINT uq_notifications_dedupe_channel UNIQUE (dedupe_key, channel),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('LOG', 'EMAIL')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_notifications_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_notifications_order ON notifications (order_number);
CREATE INDEX idx_notifications_customer_created ON notifications (customer_id, created_at);
CREATE INDEX idx_notifications_status_updated ON notifications (status, updated_at);
