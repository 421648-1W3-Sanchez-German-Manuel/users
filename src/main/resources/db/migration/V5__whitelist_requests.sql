CREATE TABLE whitelist_requests (
    id               CHAR(36)     NOT NULL PRIMARY KEY,
    requested_email  VARCHAR(255) NOT NULL,
    requested_by     CHAR(36)     NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    resolved_by      CHAR(36)     NULL,
    rejection_reason VARCHAR(500) NULL,
    created_at       DATETIME(6)  NOT NULL,
    resolved_at      DATETIME(6)  NULL,
    -- DEC-29: unique only among the PENDING ones. Same technique as DEC-21:
    -- two professors asking for the same e-mail do not open two requests.
    pending_email    VARCHAR(255) AS (IF(status = 'PENDING', requested_email, NULL)) STORED,
    UNIQUE KEY uq_whitelist_request_pending (pending_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
