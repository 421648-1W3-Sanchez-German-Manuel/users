package ar.edu.utn.frc.tup.p4.usersservice.auth;

import java.util.Map;
import java.util.Set;

/** Closed catalogue of service scopes that can be issued over HTTP. */
public final class ScopeCatalog {

    private static final Map<String, String> ISSUABLE_SCOPES = Map.of(
            "users.profile.read", "users-service");

    private ScopeCatalog() {
    }

    public static boolean esEmitible(String scope) {
        return ISSUABLE_SCOPES.containsKey(scope);
    }

    public static String audienceDe(String scope) {
        return ISSUABLE_SCOPES.get(scope);
    }

    public static Set<String> emitibles() {
        return ISSUABLE_SCOPES.keySet();
    }
}
