package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * DEC-12 - the envelope contract for the whole platform.
 */
public record EventEnvelope<T>(String eventId, String eventType, String timestamp,
                               String producer, T payload) {

    public static final String PRODUCER = "tema-01-users";

    public static <T> EventEnvelope<T> de(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now().toString(),
                PRODUCER,
                payload);
    }
}
