CREATE TABLE service_clients (
    id               CHAR(36)     NOT NULL PRIMARY KEY,
    client_id        VARCHAR(100) NOT NULL,
    secret_hash      VARCHAR(72)  NOT NULL,
    description      VARCHAR(255) NULL,
    created_at       DATETIME(6)  NOT NULL,
    deleted_at       DATETIME(6)  NULL,
    active_client_id VARCHAR(100) AS (IF(deleted_at IS NULL, client_id, NULL)) STORED,
    UNIQUE KEY uq_service_client_active (active_client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
