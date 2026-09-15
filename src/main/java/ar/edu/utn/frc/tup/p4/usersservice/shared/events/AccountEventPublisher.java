package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes standard Kafka events to the transactional outbox.
 */
@Component
public class AccountEventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    public AccountEventPublisher(OutboxRepository outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(
            String topic,
            String messageKey,
            String eventType,
            int eventVersion,
            String aggregateType,
            UUID aggregateId,
            Object payload) {
        EventEnvelope<Object> envelope =
                EventEnvelope.create(eventType, eventVersion, payload);
        try {
            outbox.save(OutboxEvent.pending(
                    envelope.eventId(),
                    envelope.eventType(),
                    aggregateType,
                    aggregateId,
                    topic,
                    messageKey,
                    mapper.writeValueAsString(envelope)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize event " + eventType, exception);
        }
    }
}
