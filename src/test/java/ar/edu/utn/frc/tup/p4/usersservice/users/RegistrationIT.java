package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.OutboxRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ErrorTypes;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.EmailWhitelist;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.EmailWhitelistRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DoD #2: student registration includes the invitation code in the same form;
 * the account moves from PENDING_EMAIL to PENDING_COURSE when the email is
 * activated, and STUDENT-REGISTERED is published only then (DEC-34). DoD #17
 * (registration half): without accepted terms, 400 (DEC-11); the other half
 * (ADMIN deactivation) belongs to AdminRulesIT.
 */
@Import(TestActivationSpy.Config.class)
class RegistrationIT extends AbstractIntegrationTest {

    @Autowired RegistrationService registration;
    @Autowired UserRepository repo;
    @Autowired EmailWhitelistRepository whitelist;
    @Autowired OutboxRepository outbox;
    @Autowired TestActivationSpy mailSpy;
    @Autowired ObjectMapper mapper;

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@utn.edu.ar";
    }

    /**
     * MySQL normalizes JSON when storing it (adding spaces after ":" and ","),
     * so the string read with findAll() is not byte-for-byte what Jackson wrote
     * when publishing. Comparing a literal substring is brittle; parse the
     * actual envelope instead.
     */
    private boolean outboxHasStudentRegistered(String userId) {
        return outbox.findAll().stream().anyMatch(e -> {
            try {
                var node = mapper.readTree(e.getPayload());
                return "STUDENT-REGISTERED".equals(node.path("eventType").asText())
                        && userId.equals(node.path("payload").path("userId").asText());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    @Test
    void student_registration_remains_PENDING_EMAIL_and_does_not_publish_STUDENT_REGISTERED_yet() {
        String email = uniqueEmail("registration");
        registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", "v1");

        String userId = repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId().toString();
        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_EMAIL);

        // DEC-34: the activation email is already queued, but the business event
        // identified by THIS account's userId does not exist yet.
        assertThat(outboxHasStudentRegistered(userId)).isFalse();
    }

    @Test
    void activation_moves_to_PENDING_COURSE_and_only_then_publishes_STUDENT_REGISTERED() {
        String email = uniqueEmail("event");
        registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", "v1");
        String userId = repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId().toString();

        registration.activate(mailSpy.latestActivationToken());

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_COURSE);
        assertThat(outboxHasStudentRegistered(userId))
                .as("STUDENT-REGISTERED must exist after activation with this account userId")
                .isTrue();
    }

    @Test
    void a_whitelisted_professor_registers_like_a_student() {
        String email = uniqueEmail("professor");
        whitelist.saveAndFlush(EmailWhitelist.create(email, Role.PROFESSOR, UUID.randomUUID()));

        registration.registerProfessor("Juan", "Diaz", email, "validpassword1", "v1");

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_EMAIL);
    }

    @Test
    void a_whitelisted_GESTOR_registers_like_a_student() {
        String email = uniqueEmail("manager");
        whitelist.saveAndFlush(EmailWhitelist.create(email, Role.GESTOR, UUID.randomUUID()));

        registration.registerManager("Gustavo", "Estor", email, "validpassword1", "v1");

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_EMAIL);
        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getRole()).isEqualTo(Role.GESTOR);
    }

    /**
     * An email whitelisted only as PROFESSOR does not enable GESTOR
     * registration: each role has its own row
     * (existsByEmailAndRoleAndDeletedAtIsNull).
     */
    @Test
    void a_GESTOR_with_a_PROFESSOR_whitelist_entry_is_rejected() {
        String email = uniqueEmail("professor-not-manager");
        whitelist.saveAndFlush(EmailWhitelist.create(email, Role.PROFESSOR, UUID.randomUUID()));

        assertThatThrownBy(() -> registration.registerManager("Gustavo", "Estor", email, "validpassword1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void a_professor_outside_the_whitelist_is_rejected() {
        String email = uniqueEmail("not-professor");

        assertThatThrownBy(() -> registration.registerProfessor("Juan", "Diaz", email, "validpassword1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void registration_without_accepting_the_current_terms_returns_400() {
        String email = uniqueEmail("no-terms");

        assertThatThrownBy(() -> registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", "v0-old"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void a_password_that_does_not_meet_the_policy_returns_400() {
        String email = uniqueEmail("weak-password");

        assertThatThrownBy(() -> registration.registerStudent("Ana", "Perez", "76543", email,
                "short", "PROG4-2026-A1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void two_registrations_with_the_same_active_email_fail_with_conflict() {
        String email = uniqueEmail("duplicate");
        registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", "v1");

        assertThatThrownBy(() -> registration.registerStudent("Other", "Name", "11111", email,
                "validpassword1", "PROG4-2026-A1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void a_student_with_a_non_institutional_domain_is_rejected() {
        // The test profile allows utn.edu.ar; gmail.com is outside it.
        String email = "outsider-" + UUID.randomUUID() + "@gmail.com";

        assertThatThrownBy(() -> registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", "v1"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("type", ErrorTypes.EMAIL_NOT_WHITELISTED)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void email_is_normalized_to_lowercase_during_registration() {
        String email = uniqueEmail("uppercase");
        registration.registerStudent("Ana", "Perez", "76543", email.toUpperCase(),
                "validpassword1", "PROG4-2026-A1", "v1");

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isPresent();
    }
}
