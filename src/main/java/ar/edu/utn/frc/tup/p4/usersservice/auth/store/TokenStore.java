package ar.edu.utn.frc.tup.p4.usersservice.auth.store;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Redis persistence contract exposed to the authentication domain. */
public interface TokenStore {

    void guardarSesion(UUID userId, String sid);

    Optional<String> sidDe(UUID userId);

    void borrarSesion(UUID userId);

    record RefreshData(UUID userId, String sid, String familyId) {
    }

    void guardarRefresh(String jti, RefreshData data, Duration ttl);

    Optional<RefreshData> refresh(String jti);

    void revocarRefresh(String jti);

    void revocarFamilia(String familyId);

    boolean familiaRevocada(String familyId);

    int incrementarFallos(String key, Duration ventana);

    void limpiarFallos(String key);

    /**
     * Contador por ventana, generico. `bucket` separa namespaces para que dos
     * limites distintos nunca compartan presupuesto. Devuelve el valor DESPUES
     * de incrementar.
     */
    int incrementarUso(String bucket, String key, Duration ventana);
}
