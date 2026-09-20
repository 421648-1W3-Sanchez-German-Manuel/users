-- The audit migrations added updated_at as NOT NULL to tables that did not
-- have it (email_whitelist, service_clients, whitelist_requests). Any writer
-- that inserts straight into SQL -- the dev seed scripts in tpi-compose, a DBA
-- one-off -- predates the column and fails with
-- "Field 'updated_at' doesn't have a default value".
--
-- The ORM always sets it explicitly, so the default only covers writers that do
-- not know about it. The column stays NOT NULL; this does not weaken the audit
-- contract, it keeps the schema usable by tools that bypass Hibernate.
ALTER TABLE email_whitelist
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE service_clients
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE whitelist_requests
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
