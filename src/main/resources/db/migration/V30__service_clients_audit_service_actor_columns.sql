ALTER TABLE service_clients_audit
    ADD COLUMN created_service VARCHAR(100) NULL AFTER created_user,
    ADD COLUMN last_updated_service VARCHAR(100) NULL AFTER last_updated_user;
