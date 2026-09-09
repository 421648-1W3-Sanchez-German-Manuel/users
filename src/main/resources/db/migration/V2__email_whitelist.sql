CREATE TABLE email_whitelist (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    email        VARCHAR(255) NOT NULL,
    added_by     CHAR(36)     NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    -- Same technique as DEC-21: an e-mail can be removed from the whitelist
    -- and added again, and only one active row per e-mail can exist.
    active_email VARCHAR(255) AS (IF(deleted_at IS NULL, email, NULL)) STORED,
    UNIQUE KEY uq_whitelist_active_email (active_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
