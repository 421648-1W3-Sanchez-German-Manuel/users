package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * DEC-34 - renders complete emails and writes their delivery events to the outbox.
 */
@Component
public class NotificationEventPublisher {

    public record PayloadEmail(String to, String asunto, String html) {
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
    public void enviar(EmailType tipo, String to, Map<String, Object> vars) {
        var mail = templates.render(tipo, vars);
        outbox.publicar(
                topics.notificaciones(),
                tipo.eventType(),
                new PayloadEmail(to, mail.asunto(), mail.html()));
    }
}
