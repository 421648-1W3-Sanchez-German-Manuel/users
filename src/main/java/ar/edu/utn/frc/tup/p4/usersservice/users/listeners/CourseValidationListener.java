package ar.edu.utn.frc.tup.p4.usersservice.users.listeners;

import ar.edu.utn.frc.tup.p4.usersservice.config.CourseValidationContractProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.CourseValidationResolvedPayload;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.ProcessedEventRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.ProcessedEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
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

    private final UserRepository repo;
    private final ProcessedEventRepository processedEvents;
    private final NotificationEventPublisher mails;
    private final CourseValidationContractProperties contract;

    public CourseValidationListener(
            UserRepository repo,
            ProcessedEventRepository processedEvents,
            NotificationEventPublisher mails,
            CourseValidationContractProperties contract) {
        this.repo = repo;
        this.processedEvents = processedEvents;
        this.mails = mails;
        this.contract = contract;
    }

    @KafkaListener(topics = "${users.kafka.topics.course-events}")
    @Transactional
    public void consume(EventEnvelope<CourseValidationResolvedPayload> envelope) {
        if (!contract.eventType().equals(envelope.eventType())) {
            log.info(
                    "KAFKA_EVENT_UNKNOWN eventId={} eventType={}",
                    envelope.eventId(),
                    envelope.eventType());
            return;
        }
        if (envelope.eventVersion() != contract.eventVersion()) {
            log.warn(
                    "KAFKA_EVENT_VERSION_UNSUPPORTED eventId={} eventType={} eventVersion={}",
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.eventVersion());
            return;
        }
        if (!contract.producer().equals(envelope.producer())) {
            log.error(
                    "KAFKA_EVENT_PRODUCER_INVALID eventId={} eventType={} producer={}",
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.producer());
            return;
        }

        CourseValidationResolvedPayload payload = envelope.payload();
        if (isBlank(payload.userId())
                || isBlank(payload.result())
                || isBlank(payload.courseId())) {
            log.error(
                    "KAFKA_EVENT_PAYLOAD_INVALID eventId={} eventType={} eventVersion={}",
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.eventVersion());
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(payload.userId());
        } catch (IllegalArgumentException exception) {
            log.error(
                    "KAFKA_EVENT_PAYLOAD_INVALID eventId={} field=userId",
                    envelope.eventId());
            return;
        }

        // DEC-13 - idempotency: INSERT and catch the duplicate. NOT a prior
        // SELECT: under REPEATABLE READ (DEC-20 r5), two concurrent consumers
        // with the same eventId can BOTH see the row missing.
        try {
            processedEvents.saveAndFlush(
                    new ProcessedEvent(envelope.eventId().toString(), envelope.eventType()));
        } catch (DataIntegrityViolationException alreadyProcessed) {
            log.debug("KAFKA_EVENT_DUPLICATE eventId={}", envelope.eventId());
            return;
        }

        // DEC-09: result and courseId are used and then discarded.
        // Courses owns that data; duplicating it would be a second source of
        // truth for the same fact.
        repo.findByIdAndDeletedAtIsNull(userId).ifPresent(u -> {
            u.activateAfterCourseValidation();   // No-op if already ACTIVE.
            repo.save(u);
            mails.send(EmailType.WHITELISTING_RESOLVED, u.getId(), u.getEmail(),
                    Map.of("firstNames", u.getFirstNames()));
        });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
