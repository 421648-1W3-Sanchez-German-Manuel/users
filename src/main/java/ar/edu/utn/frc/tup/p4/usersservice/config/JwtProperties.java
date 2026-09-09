package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "users.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTtl,
        Duration refreshTtl,
        Duration serviceTtl,
        String privateKeyPath,
        String publicKeysDir,
        String activeKid) {
}
