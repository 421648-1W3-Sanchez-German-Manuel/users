package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEC-45b - writes events to the outbox instead of publishing directly to Kafka.
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
    public void publicar(String topic, String eventType, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.de(eventType, payload);
        try {
            outbox.save(OutboxEvent.pending(
                    envelope.eventId(),
                    topic,
                    mapper.writeValueAsString(envelope)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar el evento " + eventType, exception);
        }
    }
}
