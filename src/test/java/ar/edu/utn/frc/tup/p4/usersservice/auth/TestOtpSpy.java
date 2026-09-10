package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.EmailOtpProvider;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

/**
 * Decorates the real provider to capture the code. It never logs it: it only
 * keeps it in memory for the assert. The real code still travels the same
 * camino de produccion.
 */
public class TestOtpSpy implements SecondFactorProvider {

    private final EmailOtpProvider real;
    private volatile String ultimo;

    public TestOtpSpy(EmailOtpProvider real) { this.real = real; }

    @Override public String generarDesafio(UUID userId, String email, String firstNames) {
        this.ultimo = real.generarDesafio(userId, email, firstNames);
        return ultimo;
    }

    @Override public void verificar(UUID userId, String code) { real.verificar(userId, code); }

    public String ultimoCodigo() { return ultimo; }

    /**
     * ONE single registration: the bean is both SecondFactorProvider (@Primary,
     * injected into AuthService) and TestOtpSpy (injected into the tests). If
     * registrara ademas con @Component habria dos instancias y el spy
     * would capture codes from one nobody uses.
     */
    @TestConfiguration
    public static class Config {
        @Bean @Primary
        TestOtpSpy spy(EmailOtpProvider real) { return new TestOtpSpy(real); }
    }
}