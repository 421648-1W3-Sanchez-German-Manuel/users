package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Common Kafka event envelope defined by the platform standard.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant timestamp,
        String producer,
        T payload) {

    public static final String PRODUCER = "tema-01-users";
    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("[A-Z0-9]+(?:-[A-Z0-9]+)*");

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(timestamp, "timestamp is required");
        Objects.requireNonNull(producer, "producer is required");
        Objects.requireNonNull(payload, "payload is required");
        if (!EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType must use uppercase words separated by hyphens");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be a positive integer");
        }
        if (producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
    }

    public static <T> EventEnvelope<T> create(String eventType, int eventVersion, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                eventVersion,
                Instant.now(),
                PRODUCER,
                payload);
    }
}
