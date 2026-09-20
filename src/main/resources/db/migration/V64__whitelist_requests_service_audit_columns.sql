ALTER TABLE whitelist_requests
    ADD COLUMN created_service VARCHAR(100) NULL,
    ADD COLUMN last_updated_service VARCHAR(100) NULL;
