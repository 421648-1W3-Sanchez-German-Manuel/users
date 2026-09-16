package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * secure=false solo tiene sentido en este docker-compose, que no termina TLS
 * en ningun lado (nginx escucha :3000 en HTTP plano). Un Set-Cookie con
 * Secure=true nunca viaja por HTTP, asi que en cualquier despliegue con TLS
 * real esto tiene que volver a true.
 */
@ConfigurationProperties(prefix = "users.cookie")
public record CookieProperties(boolean secure) {
}
