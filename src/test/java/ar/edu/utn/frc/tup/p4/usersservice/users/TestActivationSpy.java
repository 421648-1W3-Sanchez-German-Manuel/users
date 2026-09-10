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

/**
 * Captura el token del enlace de activacion.
 *
 * Los tres spies declaran un @Primary (NotificationEventPublisher o
 * SecondFactorProvider), asi que NINGUN test puede importar dos Config a la
 * vez. No hace falta: ningun flujo necesita capturar el token de reset y el de
 * activacion en el mismo test.
 */
public class TestActivationSpy extends NotificationEventPublisher {

    private final NotificationEventPublisher real;
    private volatile String ultimoTokenActivacion;

    // El constructor del padre no se usa: toda la logica la delega en real.
    public TestActivationSpy(NotificationEventPublisher real) {
        super(null, null, null);
        this.real = real;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enviar(EmailType tipo, String to, Map<String, Object> vars) {
        real.enviar(tipo, to, vars);
        if (tipo == EmailType.ACCOUNT_ACTIVATION) {
            this.ultimoTokenActivacion = tokenDe(vars);
        }
    }

    /**
     * El enlace se arma al renderizar y apunta al FRONTEND, no a la API
     * (RF-USR-06): de ahi se saca el query param, no del cuerpo del mail.
     */
    private static String tokenDe(Map<String, Object> vars) {
        String enlace = (String) vars.get("enlace");
        if (enlace == null) return null;
        int idx = enlace.indexOf("token=");
        return idx >= 0 ? enlace.substring(idx + "token=".length()) : null;
    }

    public String ultimoTokenActivacion() { return ultimoTokenActivacion; }

    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestActivationSpy activationSpy(
                @Qualifier("notificationEventPublisher") NotificationEventPublisher real) {
            return new TestActivationSpy(real);
        }
    }
}
