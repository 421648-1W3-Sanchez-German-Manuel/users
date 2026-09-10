package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailTemplateService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * Decorates the real publisher to capture the reset link token. It never logs
 * it: it only keeps it in memory for the assert. The real e-mail still travels
 * the same camino de produccion.
 *
 * <p>Captures ONLY {@code EmailType.RESET_PASSWORD}. The activation token
 * travels the same mechanism but belongs to another lot (U17 has its own
 * {@code TestActivationSpy}): this spy does not branch to serve it.
 */
public class TestResetSpy extends NotificationEventPublisher {

    private volatile String ultimoEnlace;

    public TestResetSpy(EmailTemplateService templates, AccountEventPublisher outbox,
                        KafkaTopicsProperties topics) {
        super(templates, outbox, topics);
    }

    @Override
    public void enviar(EmailType tipo, String to, Map<String, Object> vars) {
        if (tipo == EmailType.RESET_PASSWORD) {
            this.ultimoEnlace = (String) vars.get("enlace");
        }
        super.enviar(tipo, to, vars);
    }

    /** The token inside the captured link, or null if nothing was captured yet. */
    public String ultimoToken() {
        return ultimoEnlace == null ? null : ultimoEnlace.substring(ultimoEnlace.indexOf("token=") + 6);
    }

    /**
     * ONE single registration: TestResetSpy is a subtype of
     * NotificationEventPublisher, and here it registers itself as @Primary so
     * PasswordService receives IT instead of the real publisher. If it also
     * registered with @Component there would be two instances and the spy
     * would capture links from one nobody uses.
     */
    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestResetSpy spy(EmailTemplateService templates, AccountEventPublisher outbox,
                         KafkaTopicsProperties topics) {
            return new TestResetSpy(templates, outbox, topics);
        }
    }
}