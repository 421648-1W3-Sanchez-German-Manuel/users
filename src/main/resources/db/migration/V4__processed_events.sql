-- DEC-13: the consumer is idempotent. An event id already in this table has
-- been handled, and the redelivery is acknowledged without doing the work
-- twice.
CREATE TABLE processed_events (
    event_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at DATETIME(6)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
