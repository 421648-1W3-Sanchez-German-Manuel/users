package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

/** SPEC-git-provider-linking §4.3. Empty credentials = provider disabled (DEC-GL-05). */
@ConfigurationProperties(prefix = "users.git-providers.github")
public record GitProviderProperties(
        boolean enabled,
        @Name("client-id") String clientId,
        @Name("client-secret") String clientSecret,
        @Name("redirect-uri") String redirectUri,
        String scope,
        @Name("state-ttl") Duration stateTtl) {

    /**
     * The single condition that turns GitHub linking on, shared by everything
     * that needs to ask (DEC-GL-05).
     *
     * `enabled` and `client-id` used to be independent: the adapter registered
     * on `client-id` alone while the onboarding requirement read `enabled`
     * alone. That left two broken states — `enabled=true` with no `client-id`
     * locked every user behind the onboarding gate with no way out, and a
     * `client-id` with `enabled=false` kept the endpoints live while silently
     * dropping the requirement. Both flags now gate registration together, so
     * `enabled=false` is a real kill switch and a missing `client-id` degrades
     * to "off" instead of locking the account gate.
     */
    public static final String ACTIVE_CONDITION =
            "${users.git-providers.github.enabled:false} "
            + "and '${users.git-providers.github.client-id:}'.length() > 0";
}
