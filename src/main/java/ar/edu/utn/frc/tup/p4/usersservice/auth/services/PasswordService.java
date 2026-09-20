package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.config.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PasswordService {

    private static final String CONSTANT_RESPONSE = "Si el email existe, te enviamos las instrucciones.";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** DEC-16. Deliberately short: an active reset token can be used to take over an account. */
    private static final Duration RESET_TTL = Duration.ofMinutes(15);

    private final CredentialService credentials;
    private final EphemeralTokenService ephemeralTokens;
    private final TokenStore store;
    private final NotificationEventPublisher mails;
    private final RateLimitProperties rate;
    private final String frontendUrl;

    public PasswordService(CredentialService credentials, EphemeralTokenService ephemeralTokens,
                           TokenStore store, NotificationEventPublisher mails,
                           RateLimitProperties rate,
                           @Value("${users.front-url:https://app.tpi.utn.frc}") String frontendUrl) {
        this.credentials = credentials; this.ephemeralTokens = ephemeralTokens;
        this.store = store; this.mails = mails;
        this.rate = rate; this.frontendUrl = frontendUrl;
    }

    @Transactional
    public void change(UUID userId, String currentPassword, String newPassword) {
        if (!credentials.verifyPasswordOf(userId, currentPassword)) throw ApiException.invalidCredentials();
        credentials.updatePassword(userId, newPassword);
        store.deleteSession(userId);     // Close old sessions.
    }

    /**
     * DEC-16 - half 1: REQUEST. It ALWAYS returns the same, e-mail or no e-mail.
     * DEC-33: this does NOT become a 6-digit code. Guessing a reset IS taking
     * an account; activating an email does not grant anyone access. Different
     * impact, different mechanism.
     */
    @Transactional
    public String requestReset(String email) {
        // Count against the limit BEFORE looking up the account and use the email
        // provided, whether it exists or not. Reversing the order would leak data:
        // only registered addresses would reach the limit. Count attempts rather
        // than failures because there is no successful outcome that can clear the budget.
        //
        // Without this, the public endpoint is unbounded: unlimited email to any
        // address and uncontrolled growth of outbox_events.
        String key = email.toLowerCase(Locale.ROOT);
        if (store.incrementUsage("reset", key, rate.resetWindow()) > rate.resetMaxRequests()) {
            throw ApiException.tooManyAttempts(rate.resetWindow());
        }

        var resetData = credentials.findForPasswordReset(email);
        if (resetData != null) {
            // Same structure as the activation link (RegistrationService):
            //   reset:{sha256(token)}   -> userId   (entry point for the link)
            //   reset:email:{email}     -> sha256   (index used to invalidate on resend)
            // Without the index, each request would leave the previous link active,
            // accumulating N valid links at the same time.
            ephemeralTokens.consume(indexKey(resetData.email()))
                    .ifPresent(previousHash -> ephemeralTokens.consume(resetKey(previousHash)));

            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String hash = hash(token);

            ephemeralTokens.save(resetKey(hash), resetData.userId().toString(), RESET_TTL);
            ephemeralTokens.save(indexKey(resetData.email()), hash, RESET_TTL);
            mails.send(EmailType.RESET_PASSWORD, resetData.userId(), resetData.email(), Map.of(
                    "firstNames", resetData.firstNames(),
                    "enlace", frontendUrl + "/reset?token=" + token));
        }
        return CONSTANT_RESPONSE;
    }

    /** DEC-16 - half 2: CONFIRM. Its own path, its own body. */
    @Transactional
    public void confirmReset(String token, String newPassword) {
        // Validate the policy BEFORE consuming the token. consume() deletes the
        // Redis key, and Redis is outside the @Transactional rollback: with the
        // previous order, entering a password that failed the policy returned 400
        // AND invalidated the link. A typing mistake forced another email request.
        PasswordPolicy.validate(newPassword);

        UUID userId = ephemeralTokens.consume(resetKey(hash(token)))   // Single-use and atomic.
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidCode);

        credentials.updatePassword(userId, newPassword);
        store.deleteSession(userId);
    }

    private String resetKey(String hash) { return "reset:" + hash; }

    private String indexKey(String email) { return "reset:email:" + email; }

    /**
     * The token is stored HASHED: access to Redis must not enable account takeover.
     * A dump, replica, backup, or MONITOR command would otherwise expose a working
     * reset link for every active request.
     *
     * Plain SHA-256 is sufficient for the same reason as in RegistrationService:
     * the token already contains 256 random bits, so there is no dictionary to
     * attack and no need to incur BCrypt's cost on the hot path.
     */
    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
