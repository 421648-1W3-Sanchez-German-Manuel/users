-- DEC-12: transactional outbox. The row is written in the SAME transaction as
-- the business change, and a poller publishes it afterwards. Kafka being down
-- can never roll back a registration.
CREATE TABLE outbox_events (
    event_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    -- The poller reads WHERE published_at IS NULL ORDER BY created_at.
    KEY idx_outbox_pending (published_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
