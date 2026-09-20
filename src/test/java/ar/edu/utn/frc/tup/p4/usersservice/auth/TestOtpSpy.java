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
import java.util.UUID;

/**
 * Captures the second-factor code from the EMAIL, just like TestResetSpy.
 *
 * <p>It previously decorated SecondFactorProvider and read the return value of
 * generateChallenge. That forced the production interface to return the
 * plaintext OTP solely so a test could see it: any future caller or a log.debug
 * of that return value would leak the second factor. The code already travels
 * in the email variables, which is where it should be captured.
 *
 * <p>Captures ONLY {@code EmailType.TWO_FACTOR_CODE}.
 */
public class TestOtpSpy extends NotificationEventPublisher {

    private volatile String lastCode;

    public TestOtpSpy(EmailTemplateService templates, AccountEventPublisher outbox,
                      KafkaTopicsProperties topics) {
        super(templates, outbox, topics);
    }

    @Override
    public void send(EmailType type, UUID userId, String to, Map<String, Object> variables) {
        if (type == EmailType.TWO_FACTOR_CODE) {
            this.lastCode = (String) variables.get("code");
        }
        super.send(type, userId, to, variables);
    }

    /** The last 2FA code sent by email, or null if none has been sent yet. */
    public String lastCode() { return lastCode; }

    /**
     * ONE registration: TestOtpSpy is a subtype of NotificationEventPublisher
     * and registers as @Primary, so EmailOtpProvider receives THIS instance
     * instead of the real publisher. Registering it with @Component as well
     * would create two instances, and the spy would capture codes from the one
     * nobody uses.
     */
    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestOtpSpy spy(EmailTemplateService templates, AccountEventPublisher outbox,
                       KafkaTopicsProperties topics) {
            return new TestOtpSpy(templates, outbox, topics);
        }
    }
}
