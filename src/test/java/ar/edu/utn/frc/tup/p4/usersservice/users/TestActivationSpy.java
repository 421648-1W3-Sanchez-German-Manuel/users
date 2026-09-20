package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Captures the activation link token.
 *
 * All three spies declare a @Primary (NotificationEventPublisher or
 * SecondFactorProvider), so NO test can import two Config classes at once.
 * There is no need to: no flow needs to capture both the reset and activation
 * tokens in the same test.
 */
public class TestActivationSpy extends NotificationEventPublisher {

    private final NotificationEventPublisher real;
    private volatile String latestActivationToken;

    // The parent constructor is unused: all logic is delegated to the real publisher.
    public TestActivationSpy(NotificationEventPublisher real) {
        super(null, null, null);
        this.real = real;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void send(EmailType type, UUID userId, String to, Map<String, Object> vars) {
        real.send(type, userId, to, vars);
        if (type == EmailType.ACCOUNT_ACTIVATION) {
            this.latestActivationToken = tokenFrom(vars);
        }
    }

    /**
     * The link is built while rendering and points to the FRONTEND, not the API
     * (RF-USR-06), so the query parameter is read from there, not the mail body.
     */
    private static String tokenFrom(Map<String, Object> vars) {
        String link = (String) vars.get("enlace");
        if (link == null) return null;
        int index = link.indexOf("token=");
        return index >= 0 ? link.substring(index + "token=".length()) : null;
    }

    public String latestActivationToken() { return latestActivationToken; }

    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestActivationSpy activationSpy(
                @Qualifier("notificationEventPublisher") NotificationEventPublisher real) {
            return new TestActivationSpy(real);
        }
    }
}
