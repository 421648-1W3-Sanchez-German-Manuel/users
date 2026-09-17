package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;

/**
 * Module boundary for storing short-lived activation and recovery tokens.
 */
public interface EphemeralTokenService {

    void save(String key, String value, Duration ttl);

    /** Reads and deletes a token atomically. */
    Optional<String> consume(String key);

    /** Reads a token without deleting it. */
    Optional<String> find(String key);
}
