-- Append-only audit ledger. quantity_delta is the change to AVAILABLE quantity:
--   INITIAL +q | ADJUSTMENT +/-q | RESERVE -q | RELEASE +q | EXPIRE +q | CONFIRM 0 (stock committed)
CREATE TABLE stock_movements (
                                 id             BIGSERIAL PRIMARY KEY,
                                 product_id     BIGINT      NOT NULL,
                                 movement_type  VARCHAR(20) NOT NULL,
                                 quantity_delta INTEGER     NOT NULL,
                                 reference      VARCHAR(64),
                                 reason         VARCHAR(255),
                                 created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_movements_product_created ON stock_movements (product_id, created_at);