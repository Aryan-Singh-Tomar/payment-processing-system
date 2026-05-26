CREATE TABLE processed_events (
    id              UUID            PRIMARY KEY,
    event_key       VARCHAR(128)    NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uniq_processed_events_key
        UNIQUE (event_key, event_type)
);

CREATE INDEX idx_processed_events_processed_at
    ON processed_events (processed_at);