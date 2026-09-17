package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

/**
 * DEC-42 - counted by e-mail. The gateway limits by IP; the keys differ, so the
 * two limits cannot be triggered by the same condition.
 *
 * Three separate, NON-interchangeable budgets:
 *
 * <ul>
 *   <li>{@code login-*}: credential failures. Cleared after a success.</li>
 *   <li>{@code reset-*}: reset requests. Counts ATTEMPTS, not failures: the
 *       endpoint is public and responds identically whether the account exists
 *       or not, so there is no "success" that could clear it.</li>
 *   <li>{@code twofactor-*}: issued 2FA challenges. Prevents someone who already
 *       has the password from flooding the account owner's inbox.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "users.ratelimit")
public record RateLimitProperties(
        @Name("login-max-failures") int loginMaxFailures,
        @Name("login-window") Duration loginWindow,
        @Name("reset-max-requests") int resetMaxRequests,
        @Name("reset-window") Duration resetWindow,
        @Name("twofactor-max-challenges") int twoFactorMaxChallenges,
        @Name("twofactor-window") Duration twoFactorWindow) { }
