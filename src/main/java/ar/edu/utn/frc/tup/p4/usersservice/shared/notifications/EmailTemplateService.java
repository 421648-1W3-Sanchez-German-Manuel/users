package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

@Service
public class EmailTemplateService {

    public record MailArmado(String asunto, String html) {
    }

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private final TemplateEngine engine;
    private final MessageSource messages;

    public EmailTemplateService(TemplateEngine engine, MessageSource messages) {
        this.engine = engine;
        this.messages = messages;
    }

    public MailArmado render(EmailType tipo, Map<String, Object> vars) {
        Context context = new Context(ES_AR);
        context.setVariables(vars);
        String html = engine.process(tipo.plantilla(), context);
        String subject = messages.getMessage(tipo.claveAsunto(), null, ES_AR);
        return new MailArmado(subject, html);
    }
}
