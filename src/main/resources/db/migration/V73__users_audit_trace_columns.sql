ALTER TABLE users_audit
    ADD COLUMN created_trace_id CHAR(32) NULL AFTER created_service,
    ADD COLUMN last_updated_trace_id CHAR(32) NULL AFTER last_updated_service;
