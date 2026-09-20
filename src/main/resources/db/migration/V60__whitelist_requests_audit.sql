CREATE TABLE whitelist_requests_audit (
    version             BIGINT       NOT NULL AUTO_INCREMENT,
    id                  CHAR(36)     NOT NULL,
    requested_email     VARCHAR(255) NOT NULL,
    requested_by        CHAR(36)     NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    resolved_by         CHAR(36)     NULL,
    rejection_reason    VARCHAR(500) NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_user        CHAR(36)     NULL,
    updated_at          DATETIME(6)  NOT NULL,
    last_updated_user   CHAR(36)     NULL,
    resolved_at         DATETIME(6)  NULL,
    lock_version        BIGINT       NOT NULL,
    PRIMARY KEY (version),
    KEY idx_whitelist_requests_audit_id_version (id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
