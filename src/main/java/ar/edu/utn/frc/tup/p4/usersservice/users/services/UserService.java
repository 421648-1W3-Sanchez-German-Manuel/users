package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository repo;
    private final CredentialService credentials;
    private final PasswordEncoder encoder;
    private final EphemeralTokenService ephemeral;
    private final AccountEventPublisher events;
    private final KafkaTopicsProperties topics;
    private final String currentTermsVersion;
    private final GitProviderLinkService gitLinks;

    public UserService(UserRepository repo, CredentialService credentials,
                       PasswordEncoder encoder, EphemeralTokenService ephemeral,
                       AccountEventPublisher events,
                       KafkaTopicsProperties topics,
                       @Value("${users.legal.terms-version}") String currentTermsVersion,
                       GitProviderLinkService gitLinks) {
        this.repo = repo;
        this.credentials = credentials;
        this.encoder = encoder;
        this.ephemeral = ephemeral;
        this.events = events;
        this.topics = topics;
        this.currentTermsVersion = currentTermsVersion;
        this.gitLinks = gitLinks;
    }

    /**
     * RF-ROL-06 step 4 - the audit event of a deactivation, on the topic this
     * service owns. Nobody else reads the users table, so without this event
     * the rest of the platform never learns that an account is gone.
     */
    public record AccountDeactivatedPayload(String userId, String role, String deactivatedBy,
                                            String deactivatedAt) {
        public AccountDeactivatedPayload {
            Objects.requireNonNull(userId, "userId is required");
            Objects.requireNonNull(role, "role is required");
            Objects.requireNonNull(deactivatedBy, "deactivatedBy is required");
            Objects.requireNonNull(deactivatedAt, "deactivatedAt is required");
        }
    }

    @Transactional(readOnly = true)
    public UserMeResponse me(UUID id) {
        User u = find(id);
        return new UserMeResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getLegajo(), u.getEmail(), u.getRole(), u.getAccountStatus(),
                u.mustChangePassword(), u.isFirstLogin(), u.isGuidedTourCompleted(),
                u.getGithubUsername(), u.getAvatarRef(), u.isEmailVerified(), u.getCreatedAt(),
                u.getTermsAcceptedAt(), u.getAcceptedTermsVersion());
    }

    @Transactional(readOnly = true)
    public ProfileResponse profile(UUID id) {
        User u = find(id);
        return new ProfileResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getGithubUsername(), u.getAvatarRef());
    }

    /**
     * ADMIN directory: every active account, newest first. A GESTOR gets the
     * same shape but scoped to PROFESSOR/GESTOR accounts — it must never see
     * ADMIN or STUDENT records here.
     */
    @Transactional(readOnly = true)
    public List<UserListItemResponse> list(UUID actorId) {
        boolean isManager = find(actorId).getRole() == Role.GESTOR;
        List<User> users = isManager
                ? repo.findByRoleInAndDeletedAtIsNullOrderByCreatedAtDesc(List.of(Role.PROFESSOR, Role.GESTOR))
                : repo.findByDeletedAtIsNullOrderByCreatedAtDesc();
        return users.stream()
                .map(u -> new UserListItemResponse(u.getId().toString(), u.getFirstNames(),
                        u.getLastNames(), u.getLegajo(), u.getEmail(), u.getRole(),
                        u.getAccountStatus(), u.getCreatedAt()))
                .toList();
    }

    /**
     * DEC-GL-11: marks the guided tour only. first_login is cleared by
     * closeOnboardingIfReady (DEC-GL-14) when the account is ready.
     */
    @Transactional
    public void completeOnboarding(UUID id, boolean tourOk) {
        User u = find(id);
        u.completeOnboarding(tourOk);
        gitLinks.closeOnboardingIfReady(u);
        repo.save(u);
    }

    /**
     * It crosses both modules with NO network in between: the reinforced confirmation
     * (password again + 2FA) belongs to auth/, the business rules to users/.
     *
     * <p>SPEC §16.3 writes the reinforcement under "baja reforzada de ADMIN",
     * and it used to be applied only when the TARGET was an ADMIN. It applies
     * to every target now. What the reinforcement protects against is a stolen
     * session, and a stolen session deactivating fifty STUDENT accounts is not
     * a smaller incident than one deactivating a single ADMIN — the deletion is
     * logical, but every one of those people is locked out until somebody
     * notices. The target's role changes what is at stake for the PLATFORM
     * (hence the last-ADMIN lock, which stays ADMIN-only); it does not change
     * how sure we have to be about WHO is asking.
     *
     * <p>It costs nothing to widen: the screen already ran the three-step flow
     * for every target and already collected a real code, so this only makes
     * the service check what the UI was always sending.
     *
     * <p>The second factor is verified HERE and not in a filter: the code is
     * single-use, so consuming it has to happen in the same transaction that
     * performs the deactivation. Verifying it earlier would burn the code on a
     * request that then fails a business rule — which is why it goes LAST, after
     * every check that can reject: the role checks, the written confirmation,
     * the password and the last-ADMIN lock.
     *
     * <p>That ordering is not cosmetic. The consume happens in Redis, which
     * {@code @Transactional} does not roll back, so any rejection placed after
     * it costs the operator a valid code and a fresh challenge for an operation
     * that never took place.
     *
     * <p>It reaches {@code auth/} through {@link EphemeralTokenService} and not
     * through {@code SecondFactorProvider} or {@code TokenStore} directly: SPEC
     * §5.3 rule U4 makes that interface the only one {@code users/} may import
     * from {@code auth/}.
     */
    @Transactional
    public void deactivate(UUID actorId, UUID targetId, AdminDeactivationRequest req) {
        User target = find(targetId);

        // A GESTOR manages PROFESSOR and GESTOR accounts only - never ADMIN or STUDENT.
        if (find(actorId).getRole() == Role.GESTOR
                && target.getRole() != Role.PROFESSOR && target.getRole() != Role.GESTOR) {
            throw ApiException.accessDenied();
        }

        if (find(actorId).getRole() == Role.GESTOR && actorId.equals(targetId)) {
            throw ApiException.validation("A GESTOR cannot deactivate their own account.");
        }

        if (target.getRole() == Role.ADMIN && actorId.equals(targetId)) {
            throw ApiException.validation("An ADMIN cannot deactivate their own account.");
        }

        // ---- RF-ROL-06 step 1: reinforced identity. EVERY target. ----------
        // RF-ROL-06 / DEC-11: written confirmation, not just a button.
        if (!target.getEmail().equalsIgnoreCase(req.usernameConfirmation())) {
            throw ApiException.validation(
                    "Enter the exact username of the account you are deactivating to confirm.");
        }
        if (!credentials.verifyPasswordOf(actorId, req.password())) {
            throw ApiException.invalidCredentials();
        }

        // The password alone proves nothing that a stolen session does not
        // already have: the point of the second factor is that whoever is
        // asking still holds the operator's mailbox. The challenge is the one
        // the screen triggers right before this call, through the normal login
        // endpoint.
        //
        // The field was in the DTO from day one, @NotBlank, and nothing ever
        // read it: any six characters passed. The screen collected a real code
        // and even handled `invalid-code`, an error this service could not
        // return.
        // ---- step 2: system integrity. ADMIN-only, and that IS about role. --
        // DEC-20 rule 5: the count goes WITH A LOCK, in this same transaction.
        //
        // It runs BEFORE the second factor is consumed, and the order is the
        // whole point: the code is single-use and lives in Redis, which
        // @Transactional cannot roll back. Consuming it first meant that an
        // operator rejected by this lock lost a perfectly valid code and had to
        // request a fresh challenge to retry something that never happened.
        // The lock is a business rule like the ones above it, so it belongs on
        // the same side of the consume as they are.
        if (target.getRole() == Role.ADMIN && repo.lockActive(Role.ADMIN).size() <= 1) {
            throw ApiException.lastAdmin();
        }

        ephemeral.verifySecondFactor(actorId, req.twoFactorCode());

        // DEC-GL-17: free the provider accounts before the row is deactivated.
        gitLinks.unlinkAllActive(targetId);

        target.deactivate();
        repo.save(target);

        // DEC-22: deactivation is one of the two deletions of session:{userId}
        // — the gateway's RedisSessionRepository says so in its own javadoc, and
        // it was the half that was missing. Without it the person keeps working
        // with the access token they already hold for up to a full access-ttl
        // (10 min), because the gateway's AccountStateGuard reads `est` FROM THE
        // TOKEN, not from this table. It also kills the refresh: AuthService
        // step 3 rejects a refresh whose session no longer exists.
        //
        // Run after commit, not here: a concurrent login between this line and
        // the commit could still read the pre-deactivation row, issue a session,
        // and never see it revoked. Deleting after commit closes that window —
        // any session created before the commit is gone once it lands, and any
        // login after the commit already sees the deactivated row.
        deleteSessionAfterCommit(targetId);

        events.publish(topics.userEvents(), targetId.toString(), "ACCOUNT-DEACTIVATED", 1,
                "user", targetId,
                new AccountDeactivatedPayload(targetId.toString(), target.getRole().name(),
                        actorId.toString(), target.getDeletedAt().toString()));
    }

    @Transactional
    public void changeRole(UUID actorId, UUID targetId, Role newRole) {
        User target = find(targetId);

        // A GESTOR can only move PROFESSOR/GESTOR accounts between those two
        // roles: it can neither touch an existing ADMIN or STUDENT, nor grant
        // ADMIN or STUDENT.
        boolean outOfScope = (target.getRole() != Role.PROFESSOR && target.getRole() != Role.GESTOR)
                || (newRole != Role.PROFESSOR && newRole != Role.GESTOR);
        if (find(actorId).getRole() == Role.GESTOR && outOfScope) {
            throw ApiException.accessDenied();
        }

        if (find(actorId).getRole() == Role.GESTOR && actorId.equals(targetId)) {
            throw ApiException.validation("A GESTOR cannot change their own role.");
        }

        if (target.getRole() == Role.ADMIN && newRole != Role.ADMIN
                && repo.lockActive(Role.ADMIN).size() <= 1) {
            throw ApiException.lastAdmin();
        }
        target.changeRole(newRole);
        repo.save(target);

        // Same reasoning as deactivate() (DEC-22): the gateway's AccountStateGuard
        // reads the role from the access token, not from this table. Without
        // closing session:{userId} a demoted ADMIN keeps ROLE_ADMIN on every
        // request for up to a full access-ttl (10 min) after the demotion is
        // already committed here. This also forces a fresh login, so the next
        // token issued carries newRole instead of the stale one.
        //
        // Deferred to after commit for the same reason as deactivate(): deleting
        // it here, before newRole is durable, leaves a window where a concurrent
        // login reads the still-committed old role and issues a fresh session
        // that this call never sees.
        deleteSessionAfterCommit(targetId);
    }

    /**
     * Deletes {@code session:{userId}} once the enclosing transaction commits,
     * instead of immediately. Both {@link #deactivate} and {@link #changeRole}
     * change the row that {@code AccountStateGuard} would otherwise still read
     * as authoritative if a login raced in before the commit; running the
     * deletion after commit means every session in existence once this returns
     * reflects the new state, not the one being replaced.
     */
    private void deleteSessionAfterCommit(UUID targetId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ephemeral.deleteSession(targetId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ephemeral.deleteSession(targetId);
            }
        });
    }

    /** RF-ROL-03 - this manual registration is for ADMIN only; see the DTO. */
    @Transactional
    public UUID create(String firstNames, String lastNames, String email, String password) {
        PasswordPolicy.validate(password);
        String normalized = email.toLowerCase(Locale.ROOT);
        if (repo.findByEmailAndDeletedAtIsNull(normalized).isPresent()) throw ApiException.duplicateEmail();

        User u = User.createAdmin(firstNames, lastNames, normalized, encoder.encode(password), currentTermsVersion);
        repo.saveAndFlush(u);
        return u.getId();
    }

    private User find(UUID id) {
        return repo.findByIdAndDeletedAtIsNull(id).orElseThrow(ApiException::accessDenied);
    }
}
