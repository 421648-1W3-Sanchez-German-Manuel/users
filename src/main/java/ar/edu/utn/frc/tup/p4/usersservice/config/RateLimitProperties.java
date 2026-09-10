package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

/** DEC-42 - it counts FAILURES per e-mail. The gateway limits by IP; different
 *  keys, so the two cannot fire on the same condition. */
@ConfigurationProperties(prefix = "users.ratelimit")
public record RateLimitProperties(
        @Name("login-max-failures") int loginMaxFallos,
        @Name("login-window") Duration loginVentana) { }