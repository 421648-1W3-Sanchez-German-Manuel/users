package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

@ConfigurationProperties(prefix = "users.otp")
public record OtpProperties(
        @Name("activation-ttl") Duration activationTtl,
        @Name("two-factor-ttl") Duration twoFactorTtl,
        @Name("max-attempts") int maxAttempts) {
}
