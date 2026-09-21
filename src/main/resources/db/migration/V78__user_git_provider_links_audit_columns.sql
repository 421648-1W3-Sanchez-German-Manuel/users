-- user_git_provider_links arrived with the GitHub linking feature (main's V42),
-- after the audit work was written. It is a domain table like the rest, so it
-- gets the same auditable shape: common audit columns, optimistic-locking
-- version, and created_at as the audit timestamp (the old linked_at was the
-- same instant under a business name).
ALTER TABLE user_git_provider_links
    ADD COLUMN created_at DATETIME(6) NULL AFTER id,
    ADD COLUMN created_user CHAR(36) NULL,
    ADD COLUMN created_service VARCHAR(100) NULL,
    ADD COLUMN created_trace_id CHAR(32) NULL,
    ADD COLUMN last_updated_user CHAR(36) NULL,
    ADD COLUMN last_updated_service VARCHAR(100) NULL,
    ADD COLUMN last_updated_trace_id CHAR(32) NULL,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

UPDATE user_git_provider_links SET created_at = linked_at WHERE created_at IS NULL;

ALTER TABLE user_git_provider_links
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    DROP COLUMN linked_at;
