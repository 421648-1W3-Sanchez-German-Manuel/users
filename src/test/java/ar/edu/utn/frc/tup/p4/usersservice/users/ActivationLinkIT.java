package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RF-USR-04 - step 2: proving possession of the e-mail is a single-use LINK,
 * single use. This IT covers release criterion #26.
 */
@Import(TestActivationSpy.Config.class)
class ActivationLinkIT extends AbstractIntegrationTest {

    @Autowired RegistrationService registration;
    @Autowired UserRepository repo;
    @Autowired TestActivationSpy mailSpy;
    @Autowired StringRedisTemplate redis;

    private void registerStudent(String email) {
        registration.registerStudent("Ana", "Perez", "76543", email,
                "validpassword1", "PROG4-2026-A1", "v1");
    }

    private AccountStatus statusOf(String email) {
        return repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getAccountStatus();
    }

    @Test
    void the_correct_link_activates_the_account() {
        registerStudent("act1@utn.edu.ar");
        assertThat(statusOf("act1@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_EMAIL);

        registration.activate(mailSpy.latestActivationToken());

        // STUDENT -> PENDING_COURSE, not ACTIVE: Courses still has to validate the legajo.
        assertThat(statusOf("act1@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void the_link_can_be_used_only_ONCE() {
        // The second click can neither reactivate nor leak that the account exists.
        registerStudent("act2@utn.edu.ar");
        String token = mailSpy.latestActivationToken();

        registration.activate(token);
        assertThatThrownBy(() -> registration.activate(token)).isInstanceOf(ApiException.class);
    }

    @Test
    void used_and_nonexistent_links_return_THE_SAME_response() {
        // Anti-enumeration: the detail does not distinguish expired, used, or fabricated links.
        registerStudent("act3@utn.edu.ar");
        String token = mailSpy.latestActivationToken();
        registration.activate(token);

        String used = capture(() -> registration.activate(token));
        String fabricated = capture(() -> registration.activate("nonexistent-token-with-sufficient-length"));
        assertThat(used).isEqualTo(fabricated);
    }

    @Test
    void resending_generates_a_new_link_and_invalidates_the_previous_one() {
        // Without the per-e-mail index the old link would stay alive in parallel.
        registerStudent("act4@utn.edu.ar");
        String oldToken = mailSpy.latestActivationToken();

        registration.resendActivation("act4@utn.edu.ar");
        String newToken = mailSpy.latestActivationToken();

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThatThrownBy(() -> registration.activate(oldToken)).isInstanceOf(ApiException.class);

        registration.activate(newToken);
        assertThat(statusOf("act4@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void resending_to_an_unknown_email_returns_the_same_response_as_a_real_one() {
        registerStudent("act5@utn.edu.ar");
        assertThat(registration.resendActivation("nobody@utn.edu.ar"))
                .isEqualTo(registration.resendActivation("act5@utn.edu.ar"));
    }

    @Test
    void the_token_is_not_stored_in_plaintext() {
        // Whoever can read Redis must not be able to activate other people's accounts.
        registerStudent("act6@utn.edu.ar");
        String token = mailSpy.latestActivationToken();

        assertThat(redis.hasKey("activacion:" + token)).isFalse();
        registration.activate(token);   // The token from the email still works.
        assertThat(statusOf("act6@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void missingInvitationCodeReturnsAControlledError() {
        String email = "act7@utn.edu.ar";
        registerStudent(email);
        String token = mailSpy.latestActivationToken();
        redis.delete("invitacion:" + email);

        assertThatThrownBy(() -> registration.activate(token))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(NullPointerException.class);
        assertThat(statusOf(email)).isEqualTo(AccountStatus.PENDING_EMAIL);
    }

    private String capture(Runnable action) {
        try { action.run(); return "did-not-fail"; } catch (ApiException e) { return e.getMessage(); }
    }
}
