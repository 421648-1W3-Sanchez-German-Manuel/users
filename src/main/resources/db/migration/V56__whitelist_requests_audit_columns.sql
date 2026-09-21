ALTER TABLE whitelist_requests
    ADD COLUMN created_user CHAR(36) NULL,
    ADD COLUMN last_updated_user CHAR(36) NULL,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
