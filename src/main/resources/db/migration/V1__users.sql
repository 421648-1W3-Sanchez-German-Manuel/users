CREATE TABLE users (
    id                     CHAR(36)     NOT NULL PRIMARY KEY,
    first_names            VARCHAR(100) NOT NULL,
    last_names             VARCHAR(100) NOT NULL,
    legajo                 VARCHAR(20)  NULL,
    email                  VARCHAR(255) NOT NULL,
    password_hash          VARCHAR(72)  NOT NULL,
    role                   VARCHAR(20)  NOT NULL,
    account_status         VARCHAR(20)  NOT NULL,
    email_verified         BOOLEAN      NOT NULL DEFAULT FALSE,
    must_change_password   BOOLEAN      NOT NULL DEFAULT FALSE,
    github_username        VARCHAR(100) NULL,
    avatar_ref             VARCHAR(255) NULL,
    first_login            BOOLEAN      NOT NULL DEFAULT TRUE,
    guided_tour_completed  BOOLEAN      NOT NULL DEFAULT FALSE,
    terms_version_accepted VARCHAR(20)  NULL,
    terms_accepted_at      DATETIME(6)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    deleted_at             DATETIME(6)  NULL,
    -- DEC-21: MySQL has no partial unique indexes. This column holds the
    -- e-mail only while the row is active, and NULL once it is deactivated.
    -- Because MySQL treats every NULL in a unique index as distinct, all the
    -- deactivated rows coexist and only the active accounts compete.
    active_email           VARCHAR(255) AS (IF(deleted_at IS NULL, email, NULL)) STORED,
    UNIQUE KEY uq_users_active_email (active_email),
    KEY idx_users_role (role),
    KEY idx_users_account_status (account_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
