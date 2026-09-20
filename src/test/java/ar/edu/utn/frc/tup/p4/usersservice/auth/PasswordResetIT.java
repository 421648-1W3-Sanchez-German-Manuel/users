package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-16 - two endpoints, not one. The flow manifest section 06 drew them as the same POST. */
@Import(TestResetSpy.Config.class)   // Step 4's spy, only for this test
class PasswordResetIT extends AbstractIntegrationTest {

    /**
     * MySQL and Redis are shared singletons with NO cleanup between classes
     * (AbstractIntegrationTest). With fixed addresses, any other batch that
     * uses one of them, or a repeated run in the same JVM, causes a duplicate
     * key 409 in the fixture INSERT that looks like a failure in the code under
     * test.
     */
    private static final String SUF = "-" + UUID.randomUUID() + "@utn.edu.ar";

    @Autowired PasswordService passwords;
    @Autowired CredentialService credentials;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenStore store;
    @Autowired TestResetSpy spy;      // captures the token, like TestOtpSpy
                                      // (RESET_PASSWORD only; see Step 4)

    private User createUser(String email) {
        User user = User.create("Ana", "P", email, encoder.encode("validpassword1"), Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(user);
    }

    @Test
    void requestResetReturnsTheSameResponseWhetherTheEmailExistsOrNot() {
        createUser("res1" + SUF);
        // Anti-enumeration: an attacker cannot discover which e-mails exist.
        assertThat(passwords.requestReset("res1" + SUF))
                .isEqualTo(passwords.requestReset("nobody" + SUF));
    }

    @Test
    void confirmResetChangesThePasswordAndRevokesSessions() {
        User user = createUser("res2" + SUF);
        store.saveSession(user.getId(), "old-sid", java.time.Duration.ofMinutes(10));

        passwords.requestReset("res2" + SUF);
        passwords.confirmReset(spy.lastToken(), "newvalidpassword1");

        assertThat(credentials.verifyCredentials("res2" + SUF, "newvalidpassword1")).isNotNull();
        assertThat(credentials.verifyCredentials("res2" + SUF, "validpassword1")).isNull();
        // A changed password has to close the old sessions.
        assertThat(store.findSessionId(user.getId())).isEmpty();
    }

    @Test
    void resetTokenCanOnlyBeUsedOnce() {
        createUser("res3" + SUF);
        passwords.requestReset("res3" + SUF);
        String token = spy.lastToken();

        passwords.confirmReset(token, "newvalidpassword1");
        assertThatThrownBy(() -> passwords.confirmReset(token, "anothervalidpassword2"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void resetHonorsThePasswordPolicy() {
        createUser("res4" + SUF);
        passwords.requestReset("res4" + SUF);
        assertThatThrownBy(() -> passwords.confirmReset(spy.lastToken(), "short"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void voluntaryChangeRequiresTheCurrentPassword() {
        User user = createUser("res5" + SUF);
        assertThatThrownBy(() -> passwords.change(user.getId(), "wrongpassword12", "newvalidpassword1"))
                .isInstanceOf(ApiException.class);
        passwords.change(user.getId(), "validpassword1", "newvalidpassword1");
        assertThat(credentials.verifyCredentials("res5" + SUF, "newvalidpassword1")).isNotNull();
    }

    @Test
    void fourthResetRequestForTheSameEmailReturns429() {
        createUser("res6" + SUF);
        for (int i = 0; i < 3; i++) {
            assertThat(passwords.requestReset("res6" + SUF)).isNotBlank();
        }
        assertThatThrownBy(() -> passwords.requestReset("res6" + SUF))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(429);
    }

    @Test
    void resetLimitDoesNotRevealWhetherTheAccountExists() {
        // Count attempts for the SUBMITTED email whether it exists or not. If
        // only existing accounts counted, the 429 would become an existence oracle.
        String unknownEmail = "ghost" + SUF;
        for (int i = 0; i < 3; i++) {
            assertThat(passwords.requestReset(unknownEmail)).isNotBlank();
        }
        assertThatThrownBy(() -> passwords.requestReset(unknownEmail))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(429);
    }
}
