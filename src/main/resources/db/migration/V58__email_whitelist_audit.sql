CREATE TABLE email_whitelist_audit (
    version             BIGINT       NOT NULL AUTO_INCREMENT,
    id                  CHAR(36)     NOT NULL,
    email               VARCHAR(255) NOT NULL,
    added_by            CHAR(36)     NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_user        CHAR(36)     NULL,
    updated_at          DATETIME(6)  NOT NULL,
    last_updated_user   CHAR(36)     NULL,
    deleted_at          DATETIME(6)  NULL,
    lock_version        BIGINT       NOT NULL,
    PRIMARY KEY (version),
    KEY idx_email_whitelist_audit_id_version (id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
