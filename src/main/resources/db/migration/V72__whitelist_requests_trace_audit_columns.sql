ALTER TABLE whitelist_requests
    ADD COLUMN created_trace_id CHAR(32) NULL,
    ADD COLUMN last_updated_trace_id CHAR(32) NULL;
