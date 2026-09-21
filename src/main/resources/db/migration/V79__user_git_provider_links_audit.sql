-- History table for user_git_provider_links. Mirrors every mapped column of the
-- entity and deliberately leaves out the generated uniqueness columns
-- (active_user_provider, active_provider_account): those are derived, and the
-- audit listener never copies them.
CREATE TABLE user_git_provider_links_audit (
    version                 BIGINT       NOT NULL AUTO_INCREMENT,
    id                      CHAR(36)     NOT NULL,
    user_id                 CHAR(36)     NOT NULL,
    provider                VARCHAR(20)  NOT NULL,
    external_user_id        VARCHAR(100) NOT NULL,
    username                VARCHAR(100) NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    created_user            CHAR(36)     NULL,
    created_service         VARCHAR(100) NULL,
    created_trace_id        CHAR(32)     NULL,
    updated_at              DATETIME(6)  NOT NULL,
    last_updated_user       CHAR(36)     NULL,
    last_updated_service    VARCHAR(100) NULL,
    last_updated_trace_id   CHAR(32)     NULL,
    deleted_at              DATETIME(6)  NULL,
    lock_version            BIGINT       NOT NULL,
    PRIMARY KEY (version),
    KEY idx_user_git_provider_links_audit_id_version (id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
