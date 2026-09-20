package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Redis persistence contract exposed to the authentication domain. */
public interface TokenStore {

    /**
     * Writes the CURRENT session id, with an expiry.
     *
     * <p>The key used to be written without one. Redis is the session registry
     * here and not a cache, so it must outlive every credential that can refer
     * to it — but "no expiry" is not the way to get that: it leaves one key per
     * person who ever logged in and never logged out, for as long as the
     * instance lives, and the AOF makes them survive restarts too.
     *
     * <p>The right bound is the refresh lifetime: a refresh token is the
     * longest-lived thing that can name this session, so a session nobody has
     * refreshed in that long cannot be reached by anything. See
     * {@link #touchSession} for how the window slides.
     */
    void saveSession(UUID userId, String sid, Duration ttl);

    /**
     * Extends the expiry WITHOUT touching the sid — EXPIRE, never SET.
     *
     * <p>DEC-22 says login is the only operation that writes {@code session:},
     * and that still holds: this does not write a session id, so the single
     * session invariant is untouched. It only keeps an active session from
     * expiring underneath a refresh chain that is still being used.
     */
    void touchSession(UUID userId, Duration ttl);

    Optional<String> findSessionId(UUID userId);

    void deleteSession(UUID userId);

    record RefreshData(UUID userId, String sid, String familyId) {
    }

    void saveRefresh(String jti, RefreshData data, Duration ttl);

    Optional<RefreshData> refresh(String jti);

    void revokeRefresh(String jti);

    void revokeFamily(String familyId);

    boolean isFamilyRevoked(String familyId);

    int incrementFailures(String key, Duration window);

    void clearFailures(String key);

    /**
     * Generic window-based counter. {@code bucket} separates namespaces so two
     * different limits never share a budget. Returns the value AFTER incrementing.
     */
    int incrementUsage(String bucket, String key, Duration window);
}
