package ar.edu.utn.frc.tup.p4.usersservice.users.listeners;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.ProcessedEventRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.ProcessedEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
public class CourseValidationListener {

    private static final Logger log = LoggerFactory.getLogger(CourseValidationListener.class);
    private static final String SUPPORTED_EVENT_TYPE = "COURSE-VALIDATION-RESOLVED";
    private static final int SUPPORTED_EVENT_VERSION = 1;
    private static final String EXPECTED_PRODUCER = "tema-02-cursos";

    private final ObjectMapper mapper;
    private final UserRepository repo;
    private final ProcessedEventRepository procesados;
    private final NotificationEventPublisher mails;

    public CourseValidationListener(ObjectMapper mapper, UserRepository repo,
                                   ProcessedEventRepository procesados,
                                   NotificationEventPublisher mails) {
        this.mapper = mapper; this.repo = repo;
        this.procesados = procesados; this.mails = mails;
    }

    @KafkaListener(topics = "${users.kafka.topics.course-events}")
    @Transactional
    public void consumir(String message) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = mapper.readValue(
                    message,
                    new TypeReference<EventEnvelope<JsonNode>>() {
                    });
        } catch (Exception exception) {
            log.error("KAFKA_EVENT_INVALID topic=course-events", exception);
            return;
        }

        if (!SUPPORTED_EVENT_TYPE.equals(envelope.eventType())) {
            log.info(
                    "KAFKA_EVENT_UNKNOWN eventId={} eventType={}",
                    envelope.eventId(),
                    envelope.eventType());
            return;
        }
        if (envelope.eventVersion() != SUPPORTED_EVENT_VERSION) {
            log.warn(
                    "KAFKA_EVENT_VERSION_UNSUPPORTED eventId={} eventType={} eventVersion={}",
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.eventVersion());
            return;
        }
        if (!EXPECTED_PRODUCER.equals(envelope.producer())) {
            log.error(
                    "KAFKA_EVENT_PRODUCER_INVALID eventId={} eventType={} producer={}",
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.producer());
            return;
        }

        JsonNode payload = envelope.payload();
        if (!payload.isObject()
                || !payload.path("userId").isTextual()
                || !payload.path("result").isTextual()
                || !payload.path("courseId").isTextual()) {
            log.error(
                    "KAFKA_EVENT_PAYLOAD_INVALID eventId={} eventType={} eventVersion={}",
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.eventVersion());
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(payload.path("userId").textValue());
        } catch (IllegalArgumentException exception) {
            log.error(
                    "KAFKA_EVENT_PAYLOAD_INVALID eventId={} field=userId",
                    envelope.eventId());
            return;
        }

        // DEC-13 - idempotency: INSERT and catch the duplicate. NOT a SELECT
        // previo: bajo REPEATABLE READ (DEC-20 r5) dos consumers concurrentes
        // with the same eventId can BOTH see the row missing.
        try {
            procesados.saveAndFlush(
                    new ProcessedEvent(envelope.eventId().toString(), envelope.eventType()));
        } catch (DataIntegrityViolationException yaProcesado) {
            log.debug("KAFKA_EVENT_DUPLICATE eventId={}", envelope.eventId());
            return;
        }

        // DEC-09: `result` and `courseId` are used and then discarded.
        // Cursos owns that data; duplicating it would be a second source of
        // truth for the same fact.
        repo.findByIdAndDeletedAtIsNull(userId).ifPresent(u -> {
            u.activateAfterCourseValidation();   // no-op si ya estaba ACTIVE
            repo.save(u);
            mails.send(EmailType.WHITELISTING_RESOLVED, u.getId(), u.getEmail(),
                    Map.of("firstNames", u.getFirstNames()));
        });
    }
}
