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
}
