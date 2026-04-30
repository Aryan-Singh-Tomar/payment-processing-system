CREATE TABLE payments (
                          id                  UUID            PRIMARY KEY,
                          order_id            UUID            NOT NULL,
                          idempotency_key     VARCHAR(128)    NOT NULL,
                          amount              NUMERIC(19, 4)  NOT NULL,
                          currency            VARCHAR(3)      NOT NULL,
                          status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
                          gateway_payment_id  VARCHAR(128),
                          gateway_response    TEXT,
                          failure_reason      VARCHAR(256),
                          created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
                          updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
                          version             BIGINT          NOT NULL DEFAULT 0,

                          CONSTRAINT fk_payments_order
                              FOREIGN KEY (order_id) REFERENCES orders(id),

                          CONSTRAINT chk_payments_amount_positive
                              CHECK (amount > 0),

                          CONSTRAINT chk_payments_currency_length
                              CHECK (length(currency) = 3),

                          CONSTRAINT chk_payments_status_valid
                              CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'UNKNOWN'))
);

CREATE UNIQUE INDEX uniq_payments_idempotency_key
    ON payments (idempotency_key);

CREATE INDEX idx_payments_order_id
    ON payments (order_id);

CREATE INDEX idx_payments_status
    ON payments (status);

CREATE INDEX idx_payments_created_at
    ON payments (created_at);

-- Partial unique index: an order can have many attempts, but only ONE success.
CREATE UNIQUE INDEX uniq_payments_order_success
    ON payments (order_id)
    WHERE status = 'SUCCESS';