package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * secure=false only makes sense in this docker-compose setup, which does not
 * terminate TLS anywhere (nginx listens on :3000 over plain HTTP). A Set-Cookie
 * with Secure=true never travels over HTTP, so this must be set back to true in
 * any deployment with actual TLS.
 */
@ConfigurationProperties(prefix = "users.cookie")
public record CookieProperties(boolean secure) {
}
