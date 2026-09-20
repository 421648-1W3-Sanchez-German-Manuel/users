# Microservicio: Users (Identidad)

**Producer:** `tema-01-users`

- Emitimos → notification-events (8 mails) + user-events (STUDENT-REGISTERED, ACCOUNT-DEACTIVATED)
- Recibimos → course-events (COURSE-VALIDATION-RESOLVED)

---

# Eventos a emitir

## Tópico de destino: `notification-events`

Message Key: `userId` (UUID del usuario destinatario).

Todos estos eventos usan `eventVersion: 1` y el mismo shape de payload (email ya renderizado). **Consumidor esperado: Notifications.**

### Payload común (emails)

- `to`: dirección de correo destino.
- `subject`: asunto ya renderizado.
- `html`: cuerpo HTML ya renderizado.

---

## 1. TWO-FACTOR-EMAIL-PREPARED

Se emite cuando se prepara el mail con el código de segundo factor.

```json
{
  "eventId": "a1b2c3d4-1111-4222-8333-444455556666",
  "eventType": "TWO-FACTOR-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:00:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "alumno@frc.utn.edu.ar",
    "subject": "Tu código de verificación",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 2. ACCOUNT-ACTIVATION-EMAIL-PREPARED

Se emite cuando se prepara el mail de activación de cuenta (enlace de verificación).

```json
{
  "eventId": "6d70d204-d33d-47ea-9751-f12ab7f718a2",
  "eventType": "ACCOUNT-ACTIVATION-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-11T17:32:10.456Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "alumno@frc.utn.edu.ar",
    "subject": "Activá tu cuenta",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 3. PASSWORD-RESET-EMAIL-PREPARED

Se emite cuando se prepara el mail de restablecimiento de contraseña.

```json
{
  "eventId": "7e81e315-e44e-58fb-a862-023bc80829b3",
  "eventType": "PASSWORD-RESET-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:05:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "alumno@frc.utn.edu.ar",
    "subject": "Restablecé tu contraseña",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 4. REQUEST-PENDING-EMAIL-PREPARED

Se emite cuando un alumno verificó el mail y queda pendiente de validación de curso / habilitación.

```json
{
  "eventId": "8f92f426-f55f-49ac-b973-034cd9093ac4",
  "eventType": "REQUEST-PENDING-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:10:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "alumno@frc.utn.edu.ar",
    "subject": "Tu solicitud está en revisión",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 5. ENABLING-RESOLVED-EMAIL-PREPARED

Se emite cuando se resuelve la habilitación de la cuenta (p. ej. tras validación de curso) y se notifica al usuario.

```json
{
  "eventId": "9aa3a537-a66a-4abd-c084-045de0004bd5",
  "eventType": "ENABLING-RESOLVED-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:15:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "alumno@frc.utn.edu.ar",
    "subject": "Tu cuenta fue habilitada",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 6. BREAKGLASS-ALERT-EMAIL-PREPARED

Se emite cuando se dispara una alerta de recuperación administrativa (break-glass).

```json
{
  "eventId": "0ab4a648-a77a-4b1e-d195-056ef1115ce6",
  "eventType": "BREAKGLASS-ALERT-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:20:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "admin@frc.utn.edu.ar",
    "subject": "Alerta de recuperación administrativa",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 7. WHITELIST-SUBMISSION-EMAIL-PREPARED

Se emite cuando se prepara el mail asociado al envío de una solicitud de whitelist.

```json
{
  "eventId": "1ac5a759-a88a-4c1f-e2a6-067ff2226df7",
  "eventType": "WHITELIST-SUBMISSION-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:25:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "gestor@frc.utn.edu.ar",
    "subject": "Nueva solicitud de whitelist",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## 8. WHITELIST-DECISION-EMAIL-PREPARED

Se emite cuando se prepara el mail con la decisión sobre una solicitud de whitelist.

```json
{
  "eventId": "2bd6b86a-b99b-4d20-f3b7-178aa3337ef8",
  "eventType": "WHITELIST-DECISION-EMAIL-PREPARED",
  "eventVersion": 1,
  "timestamp": "2026-09-20T18:30:00Z",
  "producer": "tema-01-users",
  "payload": {
    "to": "solicitante@frc.utn.edu.ar",
    "subject": "Resolución de tu solicitud de whitelist",
    "html": "<!DOCTYPE html>..."
  }
}
```

---

## Tópico de destino: `user-events`

Message Key: `userId`.

Estos eventos **no** son de email; van a otros consumidores (p. ej. Cursos). Los incluimos para el panorama completo del micro.

---

## 9. STUDENT-REGISTERED

Se emite cuando un alumno verifica su email y pasa a la etapa de validación de curso (`PENDING_COURSE`).

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

### Payload

- `userId`: UUID del alumno.
- `studentNumber`: legajo / número de alumno.
- `invitationCode`: código de invitación al curso usado en el registro.

---

## 10. ACCOUNT-DEACTIVATED

Se emite cuando un ADMIN o GESTOR da de baja lógica una cuenta (`DEACTIVATED`). La fila no se borra; se cierra la sesión.

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

### Payload

- `userId`: UUID de la cuenta dada de baja.
- `role`: rol al momento de la baja (`STUDENT`, `PROFESSOR`, `GESTOR`, `ADMIN`).
- `deactivatedBy`: UUID del operador que realizó la baja.
- `deactivatedAt`: fecha y hora de la baja (ISO-8601 UTC).

---

# Eventos a recibir

## Tópico de origen: `course-events`

**Producer esperado:** `tema-02-cursos`

**Consumer group:** `users-service`

---
## 1. COURSE-VALIDATION-RESOLVED

Se consume cuando Cursos resuelve la validación de un alumno. Users activa la cuenta (`PENDING_COURSE` → `ACTIVE`) sin persistir el detalle de la resolución.

```json
{
  "eventId": "3c27427f-e190-4d15-bc76-a110de54b14f",
  "eventType": "COURSE-VALIDATION-RESOLVED",
  "eventVersion": 1,
  "timestamp": "2026-09-13T23:35:00Z",
  "producer": "tema-02-cursos",
  "payload": {
    "userId": "5d1e09d8-98e2-4ee7-b763-3cbf096a503a",
    "result": "VALIDADO_PADRON",
    "courseId": "c-1"
  }
}
```

### Payload

- `userId`: UUID del alumno a validar.
- `result`: resultado de la validación (propiedad de Cursos).
- `courseId`: identificador del curso (propiedad de Cursos).

Tipos o versiones desconocidas se ignoran de forma segura. Payloads inválidos no cambian estado de negocio.

---
