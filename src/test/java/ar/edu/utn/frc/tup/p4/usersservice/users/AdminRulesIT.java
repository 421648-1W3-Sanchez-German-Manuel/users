package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.TestOtpSpy;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.OutboxRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.AdminDeactivationRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.UserListItemResponse;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WARNING: this class EMPTIES the `users` table, and it is the only place in
 * the suite that does so. The rule under test is global
 * (countActiveWithLock(ADMIN) <= 1), so unique emails are not enough: the table
 * must contain exactly the ADMIN accounts created by this test.
 *
 * There is ONE database, shared by every test class without cleanup between
 * them. If a class needs its rows to survive another class, it will not work:
 * do not depend on execution order.
 */
@Import(TestOtpSpy.Config.class)   // deactivating an ADMIN needs a REAL second factor
class AdminRulesIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired SecondFactorProvider secondFactor;
    @Autowired TestOtpSpy otpSpy;
    @Autowired TokenStore tokens;
    @Autowired OutboxRepository outbox;
    @Autowired ObjectMapper json;

    private User admin(String email) {
        User u = User.createAdmin("Ad", "Min", email, encoder.encode("validpassword1"), "v1");
        u.changePassword(encoder.encode("validpassword1"));   // clears mustChangePassword
        return repo.saveAndFlush(u);
    }

    private User withRole(Role role, String email) {
        User u = User.create("First", "Last", email, encoder.encode("validpassword1"), role, "v1");
        return repo.saveAndFlush(u);
    }

    /**
     * A request that does NOT carry a usable second factor. Good enough for
     * every case that is rejected before reaching it (role scope, self-target,
     * written confirmation) and for non-ADMIN targets, where SPEC §16.3 does
     * not apply.
     */
    private AdminDeactivationRequest confirmation(String username) {
        return new AdminDeactivationRequest("validpassword1", "123456", username);
    }

    /**
     * The full RF-ROL-06 request: it triggers a real challenge for the actor
     * and reads the code out of the email, exactly as the screen does through
     * the login endpoint right before confirming.
     */
    private AdminDeactivationRequest reinforced(User actor, String username) {
        secondFactor.generateChallenge(actor.getId(), actor.getEmail(), actor.getFirstNames());
        return new AdminDeactivationRequest("validpassword1", otpSpy.lastCode(), username);
    }

    @Test
    void the_platform_cannot_be_left_without_an_ADMIN() {
        repo.deleteAll();
        User onlyAdmin = admin("only@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(onlyAdmin.getId(), onlyAdmin.getId(),
                confirmation("only@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void an_ADMIN_cannot_deactivate_itself() {
        admin("other@utn.edu.ar");
        User a = admin("self@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(a.getId(), a.getId(), confirmation("self@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ADMIN_deactivation_requires_written_username_confirmation() {
        User actor = admin("act@utn.edu.ar");
        User target = admin("target@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(actor.getId(), target.getId(),
                confirmation("wrongly-written@utn.edu.ar")))
                .isInstanceOf(ApiException.class);

        users.deactivate(actor.getId(), target.getId(), reinforced(actor, "target@utn.edu.ar"));
        assertThat(repo.findById(target.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void ADMIN_deactivation_requires_a_valid_second_factor() {
        User actor = admin("2fa-actor@utn.edu.ar");
        User target = admin("2fa-target@utn.edu.ar");

        // Everything else about this request is correct: right password, right
        // written confirmation. Only the code is made up — which is exactly
        // what USED to be accepted, because nothing read the field.
        assertThatThrownBy(() -> users.deactivate(actor.getId(), target.getId(),
                confirmation("2fa-target@utn.edu.ar")))
                .isInstanceOf(ApiException.class);

        assertThat(repo.findById(target.getId()))
                .get().extracting(User::getAccountStatus).isNotEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void a_second_factor_code_is_single_use() {
        User actor = admin("2fa-reuse-actor@utn.edu.ar");
        User first = admin("2fa-reuse-first@utn.edu.ar");
        User second = admin("2fa-reuse-second@utn.edu.ar");

        AdminDeactivationRequest used = reinforced(actor, "2fa-reuse-first@utn.edu.ar");
        users.deactivate(actor.getId(), first.getId(), used);

        // The same code against a second target: the OTP was consumed
        // atomically, so one challenge buys exactly one destructive action.
        assertThatThrownBy(() -> users.deactivate(actor.getId(), second.getId(),
                new AdminDeactivationRequest("validpassword1", used.twoFactorCode(),
                        "2fa-reuse-second@utn.edu.ar")))
                .isInstanceOf(ApiException.class);

        assertThat(repo.findById(second.getId()))
                .get().extracting(User::getAccountStatus).isNotEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void deactivation_closes_the_session_of_the_target() {
        User manager = withRole(Role.GESTOR, "session-manager@utn.edu.ar");
        User target = withRole(Role.PROFESSOR, "session-target@utn.edu.ar");
        tokens.saveSession(target.getId(), "sid-still-open");

        users.deactivate(manager.getId(), target.getId(),
                new AdminDeactivationRequest("na", "na", "na"));

        // DEC-22. Without this the person keeps the access token they already
        // hold for up to a full access-ttl: the gateway reads `est` from the
        // token, not from this table.
        assertThat(tokens.findSessionId(target.getId())).isEmpty();
    }

    @Test
    void deactivation_publishes_an_ACCOUNT_DEACTIVATED_event() throws Exception {
        User manager = withRole(Role.GESTOR, "event-manager@utn.edu.ar");
        User target = withRole(Role.PROFESSOR, "event-target@utn.edu.ar");

        users.deactivate(manager.getId(), target.getId(),
                new AdminDeactivationRequest("na", "na", "na"));

        OutboxEvent event = outbox.findAll().stream()
                .filter(e -> target.getId().equals(e.getAggregateId()))
                .filter(e -> "ACCOUNT-DEACTIVATED".equals(e.getEventType()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("No ACCOUNT-DEACTIVATED event was written to the outbox"));

        // The message key orders every event of the same user on one partition.
        assertThat(event.getMessageKey()).isEqualTo(target.getId().toString());
        assertThat(event.getDestinationTopic()).isEqualTo("user-events");

        // Parsed, not string-matched: the envelope's serialization is not the
        // contract, its fields are (KAFKA-EVENT-CONTRACTS.md).
        JsonNode envelope = json.readTree(event.getPayload());
        assertThat(envelope.get("eventType").asText()).isEqualTo("ACCOUNT-DEACTIVATED");
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("userId").asText()).isEqualTo(target.getId().toString());
        assertThat(payload.get("role").asText()).isEqualTo(Role.PROFESSOR.name());
        assertThat(payload.get("deactivatedBy").asText()).isEqualTo(manager.getId().toString());
        assertThat(payload.get("deactivatedAt").asText()).isNotBlank();
    }

    /**
     * ONE OPERATOR PER THREAD, and that is not incidental: the second factor is
     * single-use, so one ADMIN cannot have two reinforced deactivations in
     * flight at the same time — the second one dies on the consumed code, never
     * reaching the lock this test is about. Two operators racing is the only
     * shape of this scenario that still exercises countActiveWithLock.
     */
    @Test
    void two_CONCURRENT_deactivations_cannot_leave_zero_ADMIN_accounts() throws Exception {
        repo.deleteAll();
        User a = admin("c1@utn.edu.ar");
        User b = admin("c2@utn.edu.ar");
        User actorA = admin("c3@utn.edu.ar");
        User actorB = admin("c4@utn.edu.ar");

        // Both challenges are created BEFORE the race, one per operator, and
        // sequentially: the e-mail spy keeps only the LAST code, so generating
        // them inside the threads would race over the spy instead of over the
        // lock.
        record Attempt(User actor, User target, AdminDeactivationRequest request) { }
        List<Attempt> attempts = List.of(
                new Attempt(actorA, a, reinforced(actorA, a.getEmail())),
                new Attempt(actorB, b, reinforced(actorB, b.getEmail())));

        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        for (Attempt attempt : attempts) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    users.deactivate(attempt.actor().getId(), attempt.target().getId(),
                            attempt.request());
                    successes.incrementAndGet();
                } catch (Exception ignored) { }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).isGreaterThanOrEqualTo(1);
        assertThat(successes.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void changing_the_last_ADMIN_role_is_also_blocked() {
        repo.deleteAll();
        User onlyAdmin = admin("role@utn.edu.ar");
        assertThatThrownBy(() -> users.changeRole(onlyAdmin.getId(), onlyAdmin.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_GESTOR_cannot_deactivate_an_ADMIN() {
        User manager = withRole(Role.GESTOR, "manager-deactivate-admin@utn.edu.ar");
        User target = admin("target-deactivate-admin@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(manager.getId(), target.getId(),
                confirmation("target-deactivate-admin@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_GESTOR_cannot_deactivate_a_STUDENT() {
        User manager = withRole(Role.GESTOR, "manager-deactivate-student@utn.edu.ar");
        User target = withRole(Role.STUDENT, "target-deactivate-student@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(manager.getId(), target.getId(),
                new AdminDeactivationRequest("na", "na", "na")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_GESTOR_can_deactivate_a_PROFESSOR_or_GESTOR() {
        User manager = withRole(Role.GESTOR, "manager-deactivate-ok@utn.edu.ar");
        User professor = withRole(Role.PROFESSOR, "target-deactivate-prof@utn.edu.ar");
        User otherManager = withRole(Role.GESTOR, "target-deactivate-manager@utn.edu.ar");

        users.deactivate(manager.getId(), professor.getId(), new AdminDeactivationRequest("na", "na", "na"));
        users.deactivate(manager.getId(), otherManager.getId(), new AdminDeactivationRequest("na", "na", "na"));

        assertThat(repo.findById(professor.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.DEACTIVATED);
        assertThat(repo.findById(otherManager.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void a_GESTOR_cannot_grant_or_modify_the_ADMIN_role() {
        User manager = withRole(Role.GESTOR, "manager-role-admin@utn.edu.ar");
        User admin = admin("target-role-admin@utn.edu.ar");
        User professor = withRole(Role.PROFESSOR, "target-role-to-admin@utn.edu.ar");

        assertThatThrownBy(() -> users.changeRole(manager.getId(), admin.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> users.changeRole(manager.getId(), professor.getId(), Role.ADMIN))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_GESTOR_cannot_change_a_STUDENT_role() {
        User manager = withRole(Role.GESTOR, "manager-role-student@utn.edu.ar");
        User student = withRole(Role.STUDENT, "target-role-student@utn.edu.ar");

        assertThatThrownBy(() -> users.changeRole(manager.getId(), student.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_GESTOR_can_move_accounts_between_PROFESSOR_and_GESTOR() {
        User manager = withRole(Role.GESTOR, "manager-role-ok@utn.edu.ar");
        User professor = withRole(Role.PROFESSOR, "target-role-ok@utn.edu.ar");

        users.changeRole(manager.getId(), professor.getId(), Role.GESTOR);

        assertThat(repo.findById(professor.getId()))
                .get().extracting(User::getRole).isEqualTo(Role.GESTOR);
    }

    @Test
    void a_GESTOR_list_does_not_include_ADMIN_or_STUDENT_accounts() {
        User manager = withRole(Role.GESTOR, "manager-list@utn.edu.ar");
        User otherManager = withRole(Role.GESTOR, "target-list-manager@utn.edu.ar");
        User professor = withRole(Role.PROFESSOR, "target-list-prof@utn.edu.ar");
        admin("target-list-admin@utn.edu.ar");
        withRole(Role.STUDENT, "target-list-student@utn.edu.ar");

        List<UserListItemResponse> usersList = users.list(manager.getId());

        assertThat(usersList).extracting(UserListItemResponse::role)
                .containsOnly(Role.PROFESSOR, Role.GESTOR);
        assertThat(usersList).extracting(UserListItemResponse::id)
                .contains(otherManager.getId().toString(), professor.getId().toString());
    }
}
