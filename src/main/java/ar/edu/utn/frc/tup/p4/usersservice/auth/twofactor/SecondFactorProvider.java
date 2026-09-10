package ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor;

import java.util.UUID;

/** Strategy: sumar TOTP despues no toca el login. */
public interface SecondFactorProvider {
    /** Generates the challenge and dispatches it. Returns the generated code. */
    String generarDesafio(UUID userId, String email, String firstNames);
    void verificar(UUID userId, String code);
}