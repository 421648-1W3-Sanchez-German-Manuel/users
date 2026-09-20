package ar.edu.utn.frc.tup.p4.usersservice.auth;

import java.util.Map;
import java.util.Set;

/** Closed catalogue of service scopes that can be issued over HTTP. */
public final class ScopeCatalog {

    private static final Map<String, String> ISSUABLE_SCOPES = Map.of(
            "users.profile.read", "users-service",
            "market.catalog.read", "market-service");

    private ScopeCatalog() {
    }

    public static boolean isIssuable(String scope) {
        return ISSUABLE_SCOPES.containsKey(scope);
    }

    public static String audienceFor(String scope) {
        return ISSUABLE_SCOPES.get(scope);
    }

    public static Set<String> issuableScopes() {
        return ISSUABLE_SCOPES.keySet();
    }
}
