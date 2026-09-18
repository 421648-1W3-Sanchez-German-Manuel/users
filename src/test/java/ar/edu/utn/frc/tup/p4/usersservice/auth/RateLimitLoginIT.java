package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-42, DoD criterion #30. */
class RateLimitLoginIT extends AbstractIntegrationTest {

    /**
     * MySQL and Redis are shared singletons with NO cleanup between classes
     * (AbstractIntegrationTest). With fixed addresses, any other batch that
     * uses one of them, or a repeated run in the same JVM, causes a duplicate
     * key 409 in the fixture INSERT that looks like a failure in the code under
     * test.
     */
    private static final String SUF = "-" + UUID.randomUUID() + "@utn.edu.ar";

    @Autowired AuthService auth;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void sixthFailureForTheSameEmailReturns429() {
        createUser("rl1" + SUF);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("rl1" + SUF, "verywrong1234"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
        }
        assertThatThrownBy(() -> auth.login("rl1" + SUF, "verywrong1234"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(429);
                    assertThat(((ApiException) e).getExtras()).containsKey("retryAfterSeconds");
                    assertThat(((ApiException) e).getType().toString()).endsWith("/too-many-attempts");
                });
    }

    @Test
    void successfulLoginDoesNotConsumeTheBudgetAndClearsFailures() {
        // It counts failures, not attempts: a legitimate user never hits the limit.
        createUser("rl2" + SUF);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> auth.login("rl2" + SUF, "verywrong1234"))
                    .isInstanceOf(ApiException.class);
        }
        auth.login("rl2" + SUF, "validpassword1");   // succeeds, then clears

        // All five opportunities are available again.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("rl2" + SUF, "verywrong1234"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
        }
    }

    @Test
    void limitIsPerEmailRatherThanGlobal() {
        createUser("rl3" + SUF);
        createUser("rl4" + SUF);
        for (int i = 0; i < 6; i++) {
            try { auth.login("rl3" + SUF, "verywrong1234"); } catch (ApiException ignored) { }
        }
        // The other account remains unaffected.
        assertThatThrownBy(() -> auth.login("rl4" + SUF, "verywrong1234"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
    }

    @Test
    void sixthTwoFactorChallengeForTheSameEmailReturns429() {
        // This budget is separate from failures: a correct password does not clear it.
        // Otherwise, someone who stole the password could flood the account owner's inbox.
        createUser("rl5" + SUF);
        for (int i = 0; i < 5; i++) {
            assertThat(auth.login("rl5" + SUF, "validpassword1").challengeId()).isNotBlank();
        }
        assertThatThrownBy(() -> auth.login("rl5" + SUF, "validpassword1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(429);
    }

    private void createUser(String email) {
        User user = User.create("A", "A", email, encoder.encode("validpassword1"), Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
        repo.saveAndFlush(user);
    }
}
