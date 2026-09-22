# Vault: handoff (users)

`users` is the first consumer of the shared Vault. The Vault itself, the Agents
and the contract live in `tpi-compose` (`docs/vault-contract.md`,
`docs/vault-handoff.md`); this file covers what changed here.

- Merged to `main` (PR #26). Companion PR in `tpi-compose`: `feature/vault` (#10, also merged).
- Backward compatible: no `DB_PASSWORD`/`ADMIN_BOOTSTRAP_PASSWORD` env vars means Config Tree is
  the only source, but either one still overrides it, so an IDE run without Vault keeps working.
- Deployed to the real platform server (see `tpi-compose/docs/vault-server-migration.md` for that
  part): the initial admin logged in against a Config-Tree-sourced password issued by the real Vault.

## What changed

Only `application.yml` and one test. No Java change.

- `spring.config.import: "optional:configtree:/run/secrets/config/"`.
- `spring.datasource.password: ${db-password:${DB_PASSWORD:}}`
- `users.bootstrap.password: ${admin-bootstrap-password:${ADMIN_BOOTSTRAP_PASSWORD:}}`
- `SecretFilesConfigTest` runs the real `application.yml` against a temporary
  directory, with and without the files.

The JWT keys did not need anything: `FileSystemSigningKeyProvider` reads them from
`JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEYS_DIR`, and the Vault Agent renders them at the
same paths as before (`/run/secrets/jwt-private.pem`, `/run/secrets/jwks/dev.pem`).

## Where each secret comes from

| Secret | In the compose | From an IDE, without Vault |
|---|---|---|
| DB password | file `/run/secrets/config/db-password` | `DB_PASSWORD` |
| Initial admin password | file `/run/secrets/config/admin-bootstrap-password` | `ADMIN_BOOTSTRAP_PASSWORD` (or a generated one, printed once, as always) |
| JWT keys | `/run/secrets/jwt-private.pem`, `/run/secrets/jwks/` | `./scripts/gen-dev-keys.sh`, unchanged |

To read the files from another directory: `SPRING_CONFIG_IMPORT=optional:configtree:./dir/`
(one file per secret, named after the property: `db-password`, `admin-bootstrap-password`).

## Watch out

**Spring gives environment variables precedence over Config Tree.** Where Vault is
the source, `DB_PASSWORD` and `ADMIN_BOOTSTRAP_PASSWORD` must not be set, or the
file is silently ignored. The compose does not set them. In an IDE, a `DB_PASSWORD`
left in your shell overrides the file too.

## Verified

- `SecretFilesConfigTest` fails before the change and passes after.
- `mvn clean verify`: 103 unit and 110 integration tests, 0 failures.
- Full stack against a local Vault: starts healthy, the initial admin logs in with the
  password stored in Vault, `/.well-known/jwks.json` publishes the key rendered from Vault.
