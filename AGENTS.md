# users-service · Topic 01 · Identity and Users

Owner of platform identity: registration, credentials, sessions, roles and token
issuance. **Not reachable from outside** — everything comes in through the API
gateway.

## Getting started

```bash
./scripts/gen-dev-keys.sh          # RS256 keys. Required before the first run
mvn test                           # unit tests
mvn verify                         # + the *IT suites (Testcontainers spins up MySQL, Redis, Kafka)
```

Builds with **Java 21**. There is no `mvnw`. Without the RS256 keys the
application **refuses to start**, deliberately: an identity service must not come
up with improvised keys.

## Layout

```
auth/      credentials, 2FA, tokens, sessions, password reset   (issues and validates)
users/     people, account states, roles, whitelist, sign-up    (owns the data)
shared/    errors, security, account gates, events, mail
```

`auth/` and `users/` are modules of the **same** service. They call each other
through direct method calls, never over HTTP. The boundary between them is two
interfaces — `CredentialService` and `EphemeralTokenService` — and it is covered
by a test that fails if anything crosses it another way.

## Non-negotiables

**1 · No published ports.** In the deployment definition this service declares
`expose:` and never `ports:`. It does not validate the JWT — it trusts the `X-*`
headers precisely because the gateway is the only way in. Publish port 8082 and
this works, with no password, no token and no second factor:

```bash
curl -X DELETE http://localhost:8082/api/users/{id} -H "X-User-Roles: ADMIN"
```

The absence of `ports:` **is** the security control.

**2 · Every error is `application/problem+json`, with a `type`.** Clients branch
on `type`, never on status. That includes the ones nobody writes by hand: 404,
405, a path variable that fails to convert, an unreadable body, 401 and 403. The
last two are produced by the security chain rather than the exception handler, so
they need their own entry point and access-denied handler.

**3 · Never answer 401 for anything but identity.** A 401 sends the user to the
login screen. If a typo in a URL returns 401, a frontend bug logs people out.

**4 · A gate's exit endpoint is exempt from both fine-grained gates**, never just
its own. Both conditions can be pending at once — that is exactly the case of a
newly created administrator — and exempting only one locks the account out
permanently.

**5 · Anti-enumeration.** Password reset, activation resend, and any invalid or
expired code or link return **the same response** whether the account exists or
not. If someone "improves the message" and they start to differ, it becomes an
oracle for discovering which addresses are registered.

**6 · Nothing is ever deleted.** Logical deletion through `deleted_at`.
Uniqueness checks look at active rows, not at history.

**7 · One log line per request**, carrying `requestId` and `traceId`. Never log
bodies, tokens or the `Authorization` header: a token in a log file is a stolen
token, it just takes someone reading logs.

**8 · Events are written to the outbox table in the same transaction** as the
data change, and dispatched by a poller. Publishing to the broker from inside
the transaction means a broker outage takes every write down with it.

## Kafka is a boundary, not ours

The broker is shared infrastructure. Topics and payload schemas are a **contract
agreed with the owning team**, and the one towards the Courses topic is still
open — what exists is a draft. Do not treat it as final and do not change a
payload without telling the other side.

## Known traps

- **There is no mail server.** Verification codes and activation links are queued
  in `outbox_events`.
- **Wait ~4 seconds after logging in** before using the token. The gateway caches
  session state for 3 seconds, so within that window it still holds the previous
  session id. The same applies after logging out.
- **Changing a password closes the session.** The user has to sign in again.
- **The first login after starting the stack may return 503.** BCrypt cost 12 on
  a cold JVM exceeds the circuit breaker timeout. Retrying once is enough. If it
  happens every time and not just the first, that is a real problem.

## Documentation

| Document | Contents |
|---|---|
| `docs/plans/users-service.md` | The implementation plan, task by task |
| `docs/SPEC-users-service.md` | The decisions (`DEC-xx`) and the reasoning behind them |
| `../TASK-ASSIGNMENT.md` | Who owns which files |
| `../AGENT-SETUP.md` | Getting your agent configured |
