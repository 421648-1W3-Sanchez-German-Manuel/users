-- The `role` column on email_whitelist arrived with the GESTOR feature
-- (main's V7__email_whitelist_role) after the audit table was written. The
-- audit listener copies EVERY mapped column of the entity, so the history
-- table has to carry it too or any whitelist update fails at runtime with
-- "Unknown column 'role' in 'field list'".
ALTER TABLE email_whitelist_audit
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'PROFESSOR' AFTER email;
