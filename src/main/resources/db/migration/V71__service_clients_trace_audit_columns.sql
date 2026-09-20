ALTER TABLE service_clients
    ADD COLUMN created_trace_id CHAR(32) NULL,
    ADD COLUMN last_updated_trace_id CHAR(32) NULL;
