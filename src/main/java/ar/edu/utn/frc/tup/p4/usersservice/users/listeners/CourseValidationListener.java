package ar.edu.utn.frc.tup.p4.usersservice.users.listeners;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.ProcessedEventRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.ProcessedEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @KafkaListener(topics = "${users.kafka.topics.course-validation}")
    @Transactional
    public void consumir(String message) {
        JsonNode sobre;
        try {
            sobre = mapper.readTree(message);
        } catch (Exception e) {
            log.error("EVENTO_ILEGIBLE en course-validation", e);
            return;   // veneno: no se reintenta eternamente
        }

        String eventId = sobre.path("eventId").asText();

        // DEC-13 - idempotency: INSERT and catch the duplicate. NOT a SELECT
        // previo: bajo REPEATABLE READ (DEC-20 r5) dos consumers concurrentes
        // with the same eventId can BOTH see the row missing.
        try {
            procesados.saveAndFlush(new ProcessedEvent(eventId, sobre.path("eventType").asText()));
        } catch (DataIntegrityViolationException yaProcesado) {
            log.debug("EVENTO_DUPLICADO eventId={}", eventId);
            return;
        }

        JsonNode payload = sobre.path("payload");
        UUID userId = UUID.fromString(payload.path("userId").asText());

        // DEC-09: `resultado` and `cursoId` are USED to decide and then DISCARDED.
        // Cursos owns that data; duplicating it would be a second source of
        // truth for the same fact.
        repo.findByIdAndDeletedAtIsNull(userId).ifPresent(u -> {
            u.activateAfterCourseValidation();   // no-op si ya estaba ACTIVE
            repo.save(u);
            mails.enviar(EmailType.WHITELISTING_RESOLVED, u.getEmail(),
                    Map.of("firstNames", u.getFirstNames()));
        });
    }
}
