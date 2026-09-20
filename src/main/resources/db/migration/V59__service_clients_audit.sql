CREATE TABLE service_clients_audit (
    version             BIGINT       NOT NULL AUTO_INCREMENT,
    id                  CHAR(36)     NOT NULL,
    client_id           VARCHAR(100) NOT NULL,
    description         VARCHAR(255) NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_user        CHAR(36)     NULL,
    updated_at          DATETIME(6)  NOT NULL,
    last_updated_user   CHAR(36)     NULL,
    deleted_at          DATETIME(6)  NULL,
    lock_version        BIGINT       NOT NULL,
    PRIMARY KEY (version),
    KEY idx_service_clients_audit_id_version (id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
