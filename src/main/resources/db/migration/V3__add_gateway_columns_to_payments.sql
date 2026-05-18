ALTER TABLE payments
    ADD COLUMN processed_at TIMESTAMPTZ;

-- Index to support querying stuck payments (Week 6 reconciliation):
-- "find payments stuck in PROCESSING or UNKNOWN for more than X minutes"
CREATE INDEX idx_payments_status_created_at
    ON payments (status, created_at);