package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.EmailWhitelistRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.Objects;
import java.util.UUID;

@Service
public class RegistrationService {

    private static final String CONSTANT_RESPONSE = "Si el email existe, te enviamos un enlace.";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 24 h. With a 256-bit token there is no tension between lifetime and brute force. */
    private static final Duration ACTIVATION_TTL = Duration.ofHours(24);

    private final UserRepository repo;
    private final EmailWhitelistRepository whitelist;
    private final PasswordEncoder encoder;
    private final EphemeralTokenService ephemeralTokens;
    private final NotificationEventPublisher mails;
    private final AccountEventPublisher events;
    private final KafkaTopicsProperties topics;
    private final RateLimitProperties rate;
    private final String currentTermsVersion;
    private final String frontUrl;

    public RegistrationService(UserRepository repo, EmailWhitelistRepository whitelist,
                            PasswordEncoder encoder, EphemeralTokenService ephemeralTokens,
                            NotificationEventPublisher mails, AccountEventPublisher events,
                            KafkaTopicsProperties topics, RateLimitProperties rate,
                            @Value("${users.legal.terms-version}") String currentTermsVersion,
                            @Value("${users.front-url:https://app.tpi.utn.frc}") String frontUrl) {
        this.repo = repo; this.whitelist = whitelist; this.encoder = encoder;
        this.ephemeralTokens = ephemeralTokens; this.mails = mails;
        this.events = events; this.topics = topics; this.rate = rate;
        this.currentTermsVersion = currentTermsVersion;
        this.frontUrl = frontUrl;
    }

    public record StudentRegisteredPayload(String userId, String studentNumber, String invitationCode) {
        public StudentRegisteredPayload {
            Objects.requireNonNull(userId, "userId is required");
            Objects.requireNonNull(studentNumber, "studentNumber is required");
            Objects.requireNonNull(invitationCode, "invitationCode is required");
        }
    }

    @Transactional
    public void registerStudent(String firstNames, String lastNames, String legajo, String email,
                                String password, String invitationCode, String termsVersion) {
        User u = create(firstNames, lastNames, email, password, Role.STUDENT, termsVersion);
        u.setLegajo(legajo);
        repo.saveAndFlush(u);
        // The invitation code is NOT persisted in users: it is Courses' data.
        // It lives ephemerally in Redis until the account is activated, and travels in the event.
        saveInvitationCode(u, invitationCode);
        sendActivationLink(u);
    }

    @Transactional
    public void registerProfessor(String firstNames, String lastNames, String email,
                                  String password, String termsVersion) {
        String normalized = email.toLowerCase(Locale.ROOT);
        if (!whitelist.existsByEmailAndRoleAndDeletedAtIsNull(normalized, Role.PROFESSOR)) {
            throw ApiException.emailNotWhitelisted(
                    "The email is not on the whitelist. Ask an ADMIN or GESTOR to add it.");
        }
        User u = create(firstNames, lastNames, email, password, Role.PROFESSOR, termsVersion);
        repo.saveAndFlush(u);
        sendActivationLink(u);
    }

    @Transactional
    public void registerManager(String firstNames, String lastNames, String email,
                                String password, String termsVersion) {
        String normalized = email.toLowerCase(Locale.ROOT);
        if (!whitelist.existsByEmailAndRoleAndDeletedAtIsNull(normalized, Role.GESTOR)) {
            throw ApiException.emailNotWhitelisted(
                    "The email is not on the whitelist. Ask an ADMIN or GESTOR to add it.");
        }
        User u = create(firstNames, lastNames, email, password, Role.GESTOR, termsVersion);
        repo.saveAndFlush(u);
        sendActivationLink(u);
    }

    private User create(String firstNames, String lastNames, String email, String password,
                        Role role, String termsVersion) {
        // DEC-31 / RF-NFR-09: acceptance is required and versioned.
        if (termsVersion == null || !termsVersion.equals(currentTermsVersion)) {
            throw ApiException.validation(
                    "The current Terms and Conditions must be accepted (version "
                            + currentTermsVersion + ").");
        }
        PasswordPolicy.validate(password);

        String normalized = email.toLowerCase(Locale.ROOT);
        if (repo.findByEmailAndDeletedAtIsNull(normalized).isPresent()) {
            throw ApiException.duplicateEmail();
        }
        return User.create(firstNames, lastNames, normalized, encoder.encode(password), role, termsVersion);
    }

    /**
     * RF-USR-04 - proving possession of the e-mail is a single-use LINK, not a
     * typed code. The token is 256 bits: there is nothing to brute-force, so the
     * TTL can be 24 h and no attempt counter is needed.
     *
     * It is stored HASHED under two keys:
     *   activacion:{sha256(token)}  -> userId   (used when the link is submitted)
     *   activacion:email:{email}    -> sha256   (index used to invalidate on resend)
     * Without the index, every resend would leave the old link valid too.
     */
    private void sendActivationLink(User u) {
        // On resend, the previous link expires. Two links are never valid at once.
        ephemeralTokens.consume(indexKey(u.getEmail()))
                .ifPresent(oldHash -> ephemeralTokens.consume(activationKey(oldHash)));

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token);

        ephemeralTokens.save(activationKey(hash), u.getId().toString(), ACTIVATION_TTL);
        ephemeralTokens.save(indexKey(u.getEmail()), hash, ACTIVATION_TTL);

        // The link points to the FRONTEND, not the API. Institutional email
        // scanners (Safe Links, Proofpoint) visit links before the person does:
        // activation through GET would consume the token for the entire cohort.
        // The frontend activates only when someone presses the button, which a
        // scanner does not do.
        mails.send(EmailType.ACCOUNT_ACTIVATION, u.getId(), u.getEmail(),
                Map.of("firstNames", u.getFirstNames(),
                       "enlace", frontUrl + "/activate?token=" + token));
    }

    @Transactional
    public void activate(String token) {
        UUID userId = ephemeralTokens.consume(activationKey(hash(token))) // Single-use, atomic.
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidLink);

        User u = repo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::invalidLink);
        ephemeralTokens.consume(indexKey(u.getEmail())); // Clear the index.
        u.activate();
        repo.save(u);

        if (u.getRole() == Role.STUDENT) {
            // DEC-34: publish when moving to PENDING_COURSE, not before. If the
            // email is never verified, Courses never learns of the registration.
            events.publish(
                    topics.userEvents(),
                    u.getId().toString(),
                    "STUDENT-REGISTERED",
                    1,
                    "user",
                    u.getId(),
                    new StudentRegisteredPayload(u.getId().toString(), u.getLegajo(),
                            readInvitationCode(u)));
            mails.send(EmailType.REQUEST_PENDING, u.getId(), u.getEmail(),
                    Map.of("firstNames", u.getFirstNames(),
                           "accountStatus", AccountStatus.PENDING_COURSE.name()));
        }
    }

    /** Anti-enumeration: the same response whether or not the account exists. */
    @Transactional
    public String resendActivation(String email) {
        // Counted BEFORE looking the account up, and against the address as
        // given, exist or not. Reversing the order would leak: only registered
        // addresses would ever reach the limit, so hitting it would answer the
        // question the constant response exists to refuse.
        //
        // Attempts, not failures: this endpoint has no successful outcome that
        // could clear the budget. Same shape as PasswordService.requestReset,
        // which is the other public endpoint that sends mail — this one was
        // the only one of the two without a tope, so its only ceiling was the
        // gateway's per-IP bucket. That bucket does not protect a MAILBOX:
        // changing IP keeps flooding the same address, and every request also
        // appends a row to outbox_events.
        //
        // Counted through EphemeralTokenService and not TokenStore, where the
        // counter lives: rule U4 makes that interface the only crossing from
        // users/ into auth/, and ArchitectureTest fails the build otherwise.
        String key = email.toLowerCase(Locale.ROOT);
        if (ephemeralTokens.incrementUsage("activation", key, rate.activationWindow())
                > rate.activationMaxRequests()) {
            throw ApiException.tooManyAttempts(rate.activationWindow());
        }

        repo.findByEmailAndDeletedAtIsNull(key)
                .filter(u -> u.getAccountStatus() == AccountStatus.PENDING_EMAIL)
                .ifPresent(this::sendActivationLink);
        return CONSTANT_RESPONSE;
    }

    private String activationKey(String hash) { return "activacion:" + hash; }

    private String indexKey(String email) { return "activacion:email:" + email; }

    private String invitationKey(String email) { return "invitacion:" + email; }

    /**
     * The token is stored hashed: whoever can read Redis must not be able to
     * activate another person's account. Plain SHA-256 is sufficient because
     * the token is already 256 random bits, so a dictionary is useless.
     */
    private String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    /**
     * The invitation code is NOT our data: it lives ephemerally until the
     * account is activated, with the same TTL as the link (not the OTP TTL). If
     * the link lived for 24 hours and the code for 30 minutes, a late activation
     * would publish STUDENT-REGISTERED with a null invitationCode and Courses
     * could not route the request. It is stored through EphemeralTokenService
     * (the permitted users/ boundary into Redis), not OtpService: that engine is
     * for six-digit codes verified by a person, not for carrying data between
     * two steps of the same flow.
     */
    private void saveInvitationCode(User u, String code) {
        ephemeralTokens.save(invitationKey(u.getEmail()), code, ACTIVATION_TTL);
    }

    private String readInvitationCode(User u) {
        return ephemeralTokens.consume(invitationKey(u.getEmail()))
                .orElseThrow(ApiException::invalidLink);
    }
}
