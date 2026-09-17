package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders complete emails and writes their events to the transactional outbox.
 */
@Component
public class NotificationEventPublisher {

    public record EmailPayload(String to, String subject, String html) {
        public EmailPayload {
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(subject, "subject is required");
            Objects.requireNonNull(html, "html is required");
        }
    }

    private final EmailTemplateService templates;
    private final AccountEventPublisher outbox;
    private final KafkaTopicsProperties topics;

    public NotificationEventPublisher(
            EmailTemplateService templates,
            AccountEventPublisher outbox,
            KafkaTopicsProperties topics) {
        this.templates = templates;
        this.outbox = outbox;
        this.topics = topics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void send(EmailType type, UUID userId, String to, Map<String, Object> variables) {
        var renderedEmail = templates.render(type, variables);
        outbox.publish(
                topics.notificationEvents(),
                userId.toString(),
                type.eventType(),
                1,
                "user",
                userId,
                new EmailPayload(to, renderedEmail.subject(), renderedEmail.html()));
    }
}
