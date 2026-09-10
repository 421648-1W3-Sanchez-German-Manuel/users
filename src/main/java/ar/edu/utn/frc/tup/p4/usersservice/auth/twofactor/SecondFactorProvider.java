package ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor;

import java.util.UUID;

/** Strategy: sumar TOTP despues no toca el login. */
public interface SecondFactorProvider {
    /**
     * Genera el desafio y lo despacha. NO devuelve el code: el segundo factor
     * solo tiene que existir en el mail y en Redis. Los tests lo capturan del
     * mail (TestOtpSpy), no de este contrato.
     */
    void generarDesafio(UUID userId, String email, String firstNames);
    void verificar(UUID userId, String code);
}