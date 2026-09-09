package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

@ConfigurationProperties(prefix = "users.otp")
public record OtpProperties(
        @Name("activation-ttl") Duration activacionTtl,
        @Name("two-factor-ttl") Duration dosfaTtl,
        @Name("max-attempts") int maxIntentos) {
}
