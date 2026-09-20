# Users Service Kafka Event Contracts

This document registers the Kafka contracts owned or consumed by
`tema-01-users`. It follows `KAFKA_EVENT_STANDARD.md`.

## Domain: Users

- Topic: `user-events`
- Message Key: `userId`
- Justification: all events concerning the same user must preserve their
  relative order, while different users can be distributed across partitions.
- The Message Key is outside the JSON value.
- Every event still has its own unique `eventId`.

### STUDENT-REGISTERED

- Description: published after a student verifies their email and enters the
  course-validation stage.
- Event Type: `STUDENT-REGISTERED`
- Event Version: `1`
- Producer: `tema-01-users`
- Known consumer: Courses service
- Payload fields:
  - `userId`: string UUID, required. Identifies the student.
  - `studentNumber`: string, required. Institutional student number.
  - `invitationCode`: string, required. Course invitation code supplied during
    registration.

Example:

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "STUDENT-REGISTERED",
  "eventVersion": 1,
  "timestamp": "2026-09-11T17:30:25.123Z",
  "producer": "tema-01-users",
  "payload": {
    "userId": "5d1e09d8-98e2-4ee7-b763-3cbf096a503a",
    "studentNumber": "76543",
    "invitationCode": "PROG4-2026-A1"
  }
}
```

### ACCOUNT-DEACTIVATED

- Description: published when an account is logically deactivated by an ADMIN
  or a GESTOR (`RF-ROL-06`, SPEC §16.3 step 4). The row is never deleted: the
  account moves to `DEACTIVATED` and its session is closed.
- Event Type: `ACCOUNT-DEACTIVATED`
- Event Version: `1`
- Producer: `tema-01-users`
- Known consumer: none yet. It is published because nobody else reads the
  `users` table: without it, a subsystem holding its own copy of a person keeps
  treating a deactivated account as valid.
- Payload fields:
  - `userId`: string UUID, required. The deactivated account.
  - `role`: string, required. Its role at the moment of deactivation
    (`STUDENT`, `PROFESSOR`, `GESTOR`, `ADMIN`).
  - `deactivatedBy`: string UUID, required. The operator who performed it. This
    is the audit trail, and the only record of who did it.
  - `deactivatedAt`: string ISO-8601 UTC, required.

Example:

```json
{
  "eventId": "2f8a1c3e-77b1-4a4f-9a0b-1d2e3f4a5b6c",
  "eventType": "ACCOUNT-DEACTIVATED",
  "eventVersion": 1,
  "timestamp": "2026-09-18T14:05:02.881Z",
  "producer": "tema-01-users",
  "payload": {
    "userId": "5d1e09d8-98e2-4ee7-b763-3cbf096a503a",
    "role": "STUDENT",
    "deactivatedBy": "a1b2c3d4-0000-4444-8888-99aabbccddee",
    "deactivatedAt": "2026-09-18T14:05:02.877Z"
  }
}
```

## Domain: Notifications

- Topic: `notification-events`
- Message Key: `userId`
- Justification: emails prepared for the same user must preserve their relative
  order, while notifications for different users can be processed in parallel.
- The Message Key is outside the JSON value and is not the `eventId`.
- Producer: `tema-01-users`
- Known consumer: Notifications service

All email events use version `1` and this payload:

- `to`: string, required. Destination email address.
- `subject`: string, required. Rendered email subject.
- `html`: string, required. Rendered HTML body.

Registered event types:

- `TWO-FACTOR-EMAIL-PREPARED`: a two-factor email was prepared.
- `ACCOUNT-ACTIVATION-EMAIL-PREPARED`: an account activation email was
  prepared.
- `PASSWORD-RESET-EMAIL-PREPARED`: a password reset email was prepared.
- `REQUEST-PENDING-EMAIL-PREPARED`: a pending-request email was prepared.
- `ENABLING-RESOLVED-EMAIL-PREPARED`: an account-enabling result email was
  prepared.
- `BREAKGLASS-ALERT-EMAIL-PREPARED`: an administrative recovery alert email
  was prepared.
- `WHITELIST-SUBMISSION-EMAIL-PREPARED`: a whitelist submission email was
  prepared.
- `WHITELIST-DECISION-EMAIL-PREPARED`: a whitelist decision email was
  prepared.

Example:

```json
{
  "eventId": "6d70d204-d33d-47ea-9751-f12ab7f718a2",
  "eventType": "ACCOUNT-ACTIVATION-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-11T17:32:10.456Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "student@frc.utn.edu.ar",
    "subject": "Activate your account",
    "html": "<!DOCTYPE html>..."
  }
}
```

## Consumed Contract: COURSE-VALIDATION-RESOLVED

- Domain: Courses
- Topic: `course-events`
- Event Type: `COURSE-VALIDATION-RESOLVED`
- Event Version: `1`
- Producer: `tema-02-cursos`
- Message Key: pending definition by the Courses domain owner. This service
  does not infer or redefine it.
- Payload fields:
  - `userId`: string UUID, required. Identifies the student being validated.
  - `result`: string, required. Validation result owned by Courses.
  - `courseId`: string, required. Course identifier owned by Courses.

Unknown event types and unknown versions are ignored safely. Invalid envelopes
or invalid payloads are rejected before any business state is changed.

## Transactional Outbox

Business changes and their events are persisted in the same SQL transaction in
`outbox_events`. Each row stores:

- `outbox_id`: internal UUID primary key.
- `event_id`: unique UUID copied into the envelope.
- `event_type`: stable event type.
- `aggregate_type`: affected business entity type.
- `aggregate_id`: affected entity UUID.
- `destination_topic`: domain topic.
- `message_key`: Kafka Message Key stored outside the JSON envelope.
- `payload`: complete serialized envelope.
- `status`: `PENDING`, `PUBLISHED`, or `FAILED`.
- `attempts`: failed publication attempts.
- `created_at`: event creation time.
- `published_at`: broker-confirmed publication time, when available.

The poller publishes only `PENDING` rows. It marks a row `PUBLISHED` after the
broker acknowledges it. A failed publication remains pending until the fifth
failed attempt, when it becomes `FAILED` for manual review.
