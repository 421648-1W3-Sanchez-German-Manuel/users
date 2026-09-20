package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Verifies a password in the SAME time whether or not the account exists.
 *
 * <p>Non-negotiable 5 forbids enumeration oracles, and the two credential
 * checks of this service both had one. Not in what they ANSWER — that is
 * correctly identical, and there are tests for it — but in how long they take
 * to answer it.
 *
 * <p>The shape that causes it is this one, and it reads as obviously correct:
 *
 * <pre>
 *   repo.findByEmailAndDeletedAtIsNull(email)
 *       .filter(u -&gt; encoder.matches(plainPassword, u.getPasswordHash()))
 * </pre>
 *
 * <p>{@code filter} only runs when the row was found, so an unknown e-mail
 * answers in ~1 ms and a registered one in ~100 ms — BCrypt at cost 12
 * (DEC-38) is expensive ON PURPOSE, which is exactly what makes the gap easy to
 * measure over the network. {@code POST /auth/login} became a reliable "is this
 * address registered?" endpoint, which is the very thing the identical error
 * message exists to prevent.
 *
 * <p>The fix is to spend the BCrypt anyway, against a decoy hash. It is
 * generated once at startup from random bytes rather than hardcoded, so it
 * keeps costing the same as the real comparison even if DEC-38's cost is raised
 * later — a hardcoded constant would silently stop matching the moment someone
 * changes the encoder.
 */
@Component
public class UniformCostPasswordVerifier {

    private final PasswordEncoder encoder;
    private final String decoyHash;

    public UniformCostPasswordVerifier(PasswordEncoder encoder) {
        this.encoder = encoder;
        byte[] noise = new byte[32];
        new SecureRandom().nextBytes(noise);
        // The plaintext is discarded here and nobody — this process included —
        // ever holds it, so no input can match this hash.
        this.decoyHash = encoder.encode(Base64.getEncoder().encodeToString(noise));
    }

    /**
     * @param storedHash the account's hash, or {@code null} when there is no
     *                   account. {@code null} spends the same BCrypt against
     *                   the decoy and then returns false.
     */
    public boolean matches(String rawPassword, String storedHash) {
        String candidate = rawPassword == null ? "" : rawPassword;
        if (storedHash == null) {
            encoder.matches(candidate, decoyHash);
            return false;
        }
        return encoder.matches(candidate, storedHash);
    }
}
