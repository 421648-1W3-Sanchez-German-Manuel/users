RENAME TABLE outbox_events TO outbox_event;

ALTER TABLE outbox_event
    CHANGE COLUMN event_id event_id CHAR(36) NOT NULL,
    CHANGE COLUMN topic destination_topic VARCHAR(255) NOT NULL,
    ADD COLUMN outbox_id CHAR(36) NULL FIRST,
    ADD COLUMN event_type VARCHAR(255) NULL AFTER event_id,
    ADD COLUMN aggregate_type VARCHAR(255) NULL AFTER event_type,
    ADD COLUMN aggregate_id CHAR(36) NULL AFTER aggregate_type,
    ADD COLUMN message_key VARCHAR(255) NULL AFTER destination_topic,
    ADD COLUMN status ENUM('PENDING', 'PUBLISHED', 'FAILED') NULL AFTER payload;

UPDATE outbox_event outbox
LEFT JOIN users user_account
    ON user_account.email = JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.payload.to'))
SET outbox.outbox_id = UUID(),
    outbox.event_type = CASE JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.eventType'))
        WHEN 'ALUMNO_REGISTRADO' THEN 'STUDENT-REGISTERED'
        WHEN 'EMAIL_2FA' THEN 'TWO-FACTOR-EMAIL-PREPARED'
        WHEN 'EMAIL_ACTIVACION_CUENTA' THEN 'ACCOUNT-ACTIVATION-EMAIL-PREPARED'
        WHEN 'EMAIL_RESET_PASSWORD' THEN 'PASSWORD-RESET-EMAIL-PREPARED'
        WHEN 'EMAIL_SOLICITUD_PENDIENTE' THEN 'REQUEST-PENDING-EMAIL-PREPARED'
        WHEN 'EMAIL_HABILITACION_RESUELTA' THEN 'ENABLING-RESOLVED-EMAIL-PREPARED'
        WHEN 'EMAIL_ALERTA_BREAKGLASS' THEN 'BREAKGLASS-ALERT-EMAIL-PREPARED'
        WHEN 'EMAIL_WHITELIST_SOLICITUD' THEN 'WHITELIST-SUBMISSION-EMAIL-PREPARED'
        WHEN 'EMAIL_WHITELIST_RESUELTA' THEN 'WHITELIST-DECISION-EMAIL-PREPARED'
        ELSE REPLACE(JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.eventType')), '_', '-')
    END,
    outbox.aggregate_type = 'user',
    outbox.aggregate_id = COALESCE(
        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.payload.userId')), ''),
        user_account.id,
        outbox.event_id),
    outbox.message_key = COALESCE(
        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.payload.userId')), ''),
        user_account.id,
        outbox.event_id),
    outbox.destination_topic = CASE outbox.destination_topic
        WHEN 'tema-01-users.alumno-registrado.v1' THEN 'user-events'
        WHEN 'tema-XX-notificaciones.email.v1' THEN 'notification-events'
        ELSE outbox.destination_topic
    END,
    outbox.payload = JSON_SET(
        outbox.payload,
        '$.eventVersion', 1,
        '$.eventType', CASE JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.eventType'))
            WHEN 'ALUMNO_REGISTRADO' THEN 'STUDENT-REGISTERED'
            WHEN 'EMAIL_2FA' THEN 'TWO-FACTOR-EMAIL-PREPARED'
            WHEN 'EMAIL_ACTIVACION_CUENTA' THEN 'ACCOUNT-ACTIVATION-EMAIL-PREPARED'
            WHEN 'EMAIL_RESET_PASSWORD' THEN 'PASSWORD-RESET-EMAIL-PREPARED'
            WHEN 'EMAIL_SOLICITUD_PENDIENTE' THEN 'REQUEST-PENDING-EMAIL-PREPARED'
            WHEN 'EMAIL_HABILITACION_RESUELTA' THEN 'ENABLING-RESOLVED-EMAIL-PREPARED'
            WHEN 'EMAIL_ALERTA_BREAKGLASS' THEN 'BREAKGLASS-ALERT-EMAIL-PREPARED'
            WHEN 'EMAIL_WHITELIST_SOLICITUD' THEN 'WHITELIST-SUBMISSION-EMAIL-PREPARED'
            WHEN 'EMAIL_WHITELIST_RESUELTA' THEN 'WHITELIST-DECISION-EMAIL-PREPARED'
            ELSE REPLACE(JSON_UNQUOTE(JSON_EXTRACT(outbox.payload, '$.eventType')), '_', '-')
        END),
    outbox.status = CASE
        WHEN outbox.published_at IS NOT NULL THEN 'PUBLISHED'
        WHEN outbox.attempts >= 5 THEN 'FAILED'
        ELSE 'PENDING'
    END;

UPDATE outbox_event
SET payload = JSON_SET(
        payload,
        '$.payload.subject',
        JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.asunto')))
WHERE JSON_CONTAINS_PATH(payload, 'one', '$.payload.asunto');

UPDATE outbox_event
SET payload = JSON_SET(
        payload,
        '$.payload.studentNumber',
        JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.legajo')))
WHERE JSON_CONTAINS_PATH(payload, 'one', '$.payload.legajo');

ALTER TABLE outbox_event
    DROP PRIMARY KEY,
    DROP INDEX idx_outbox_pending,
    MODIFY COLUMN outbox_id CHAR(36) NOT NULL,
    MODIFY COLUMN event_type VARCHAR(255) NOT NULL,
    MODIFY COLUMN aggregate_type VARCHAR(255) NOT NULL,
    MODIFY COLUMN aggregate_id CHAR(36) NOT NULL,
    MODIFY COLUMN message_key VARCHAR(255) NOT NULL,
    MODIFY COLUMN status ENUM('PENDING', 'PUBLISHED', 'FAILED') NOT NULL,
    ADD PRIMARY KEY (outbox_id),
    ADD UNIQUE KEY uk_outbox_event_id (event_id),
    ADD KEY idx_outbox_pending (status, created_at);
