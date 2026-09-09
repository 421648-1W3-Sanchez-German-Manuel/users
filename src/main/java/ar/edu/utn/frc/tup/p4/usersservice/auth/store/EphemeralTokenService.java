package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;

/**
 * Module boundary for storing short-lived activation and recovery tokens.
 */
public interface EphemeralTokenService {

    void guardar(String key, String valor, Duration ttl);

    /** Reads and deletes a token atomically. */
    Optional<String> consumir(String key);

    /** Reads a token without deleting it. */
    Optional<String> verificar(String key);
}
