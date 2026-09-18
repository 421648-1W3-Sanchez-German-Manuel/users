package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

@Service
public class EmailTemplateService {

    public record RenderedEmail(String subject, String html) {
    }

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private final TemplateEngine engine;
    private final MessageSource messages;

    public EmailTemplateService(TemplateEngine engine, MessageSource messages) {
        this.engine = engine;
        this.messages = messages;
    }

    public RenderedEmail render(EmailType type, Map<String, Object> variables) {
        Context context = new Context(ES_AR);
        context.setVariables(variables);
        String html = engine.process(type.template(), context);
        String subject = messages.getMessage(type.subjectKey(), null, ES_AR);
        return new RenderedEmail(subject, html);
    }
}
