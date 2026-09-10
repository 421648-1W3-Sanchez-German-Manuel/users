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
 * Captura el code del segundo factor desde el MAIL, igual que TestResetSpy.
 *
 * <p>Antes decoraba a SecondFactorProvider y lo leia del valor de retorno de
 * generarDesafio. Eso obligaba a que la interfaz de produccion devolviera el
 * OTP en claro solo para que un test pudiera verlo: cualquier caller futuro, o
 * un log.debug sobre ese retorno, filtraba el segundo factor. El code ya viaja
 * en las variables del mail, que es de donde hay que sacarlo.
 *
 * <p>Captura SOLO {@code EmailType.TWO_FACTOR_CODE}.
 */
public class TestOtpSpy extends NotificationEventPublisher {

    private volatile String ultimo;

    public TestOtpSpy(EmailTemplateService templates, AccountEventPublisher outbox,
                      KafkaTopicsProperties topics) {
        super(templates, outbox, topics);
    }

    @Override
    public void enviar(EmailType tipo, String to, Map<String, Object> vars) {
        if (tipo == EmailType.TWO_FACTOR_CODE) {
            this.ultimo = (String) vars.get("code");
        }
        super.enviar(tipo, to, vars);
    }

    /** El ultimo code de 2FA que salio por mail, o null si todavia no salio ninguno. */
    public String ultimoCodigo() { return ultimo; }

    /**
     * UN solo registro: TestOtpSpy es subtipo de NotificationEventPublisher y
     * se registra @Primary, asi que EmailOtpProvider recibe ESTE en lugar del
     * publisher real. Si ademas se registrara con @Component habria dos
     * instancias y el spy capturaria codes de una que nadie usa.
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
