package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Redis persistence contract exposed to the authentication domain. */
public interface TokenStore {

    void saveSession(UUID userId, String sid);

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
