CREATE TABLE user_git_provider_links (
    id               CHAR(36)     NOT NULL PRIMARY KEY,
    user_id          CHAR(36)     NOT NULL,
    provider         VARCHAR(20)  NOT NULL,
    external_user_id VARCHAR(100) NOT NULL,
    username         VARCHAR(100) NOT NULL,
    linked_at        DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    deleted_at       DATETIME(6)  NULL,
    -- Same technique as DEC-29: uniqueness looks at ACTIVE rows only, not history
    -- (non-negotiable 6). Without this, unlink+relink collides.
    active_user_provider    VARCHAR(64)  AS (IF(deleted_at IS NULL, CONCAT(provider, ':', user_id), NULL)) STORED,
    active_provider_account VARCHAR(150) AS (IF(deleted_at IS NULL, CONCAT(provider, ':', external_user_id), NULL)) STORED,
    UNIQUE KEY uq_git_link_active_user_provider (active_user_provider),
    UNIQUE KEY uq_git_link_active_provider_account (active_provider_account),
    KEY idx_git_link_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
