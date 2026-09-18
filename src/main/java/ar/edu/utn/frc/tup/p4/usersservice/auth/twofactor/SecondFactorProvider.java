package ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor;

import java.util.UUID;

/** Strategy interface that allows adding TOTP later without changing the login flow. */
public interface SecondFactorProvider {
    /**
     * Generates and dispatches the challenge. It does NOT return the code: the
     * second factor must exist only in the email and Redis. Tests capture it from
     * the email (TestOtpSpy), not from this contract.
     */
    void generateChallenge(UUID userId, String email, String firstNames);
    void verify(UUID userId, String code);
}
