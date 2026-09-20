ALTER TABLE email_whitelist
    ADD COLUMN created_service VARCHAR(100) NULL,
    ADD COLUMN last_updated_service VARCHAR(100) NULL;
