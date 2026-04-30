CREATE TABLE orders (
                        id              UUID            PRIMARY KEY,
                        customer_id     VARCHAR(64)     NOT NULL,
                        amount          NUMERIC(19, 4)  NOT NULL,
                        currency        VARCHAR(3)      NOT NULL,
                        status          VARCHAR(32)     NOT NULL DEFAULT 'CREATED',
                        created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
                        updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
                        version         BIGINT          NOT NULL DEFAULT 0,

                        CONSTRAINT chk_orders_amount_positive    CHECK (amount > 0),
                        CONSTRAINT chk_orders_currency_length    CHECK (length(currency) = 3),
                        CONSTRAINT chk_orders_status_valid       CHECK (status IN ('CREATED', 'PAID', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status      ON orders (status);