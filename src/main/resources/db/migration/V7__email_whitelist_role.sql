-- GESTOR whitelists PROFESSOR and GESTOR emails, same mechanism ADMIN already
-- had for PROFESSOR. Existing rows predate GESTOR, so they default to the
-- only role the whitelist supported until now.
ALTER TABLE email_whitelist ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'PROFESSOR';
