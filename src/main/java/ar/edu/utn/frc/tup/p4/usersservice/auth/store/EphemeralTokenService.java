package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.UUID;
import java.util.Optional;

/**
 * THE boundary from {@code users/} into {@code auth/} — SPEC §5, and §5.3 rule
 * U4 makes it the only interface {@code users/} may import from {@code auth/}.
 * Everything {@code users/} needs from the ephemeral (Redis) side arrives
 * through here, so the rule stays checkable by a single assertion instead of a
 * list of forbidden packages that every new {@code auth/} package escapes.
 */
public interface EphemeralTokenService {

    void save(String key, String value, Duration ttl);

    /** Reads and deletes a token atomically. */
    Optional<String> consume(String key);

    /** Reads a token without deleting it. */
    Optional<String> find(String key);

    /**
     * Verifies and CONSUMES {@code userId}'s single-use second factor, throwing
     * if it does not match. Delegates to {@code auth/}'s SecondFactorProvider:
     * the strategy (e-mail OTP today, TOTP later) stays private to {@code auth/}
     * and {@code users/} never learns which one is in play.
     */
    void verifySecondFactor(UUID userId, String code);

    /**
     * Deletes {@code userId}'s single session — DEC-22. Deactivation is one of
     * the two deletions of {@code session:{userId}}; the other is logout.
     */
    void deleteSession(UUID userId);

    /**
     * Window-based counter, returning the value AFTER incrementing.
     *
     * <p>{@code users/} needs it for {@code /resend-activation}: a PUBLIC
     * endpoint that sends mail, exactly like {@code /password/reset}, and the
     * only one of the two that had no budget of its own. The gateway's per-IP
     * bucket does not replace it — it protects the service from a flood, not a
     * MAILBOX from being targeted, since changing IP keeps hitting the same
     * address and every request appends a row to {@code outbox_events}.
     *
     * <p>It goes on this interface rather than letting {@code users/} reach for
     * {@code TokenStore}, which is where the counter actually lives: rule U4
     * makes this the single crossing, and ArchUnit enforces it. Same shape as
     * {@link #deleteSession} — a capability {@code users/} needs, exposed here,
     * implemented by delegating inside {@code auth/}.
     *
     * @param bucket namespace, so two limits never share a budget
     */
    int incrementUsage(String bucket, String key, Duration window);
}
