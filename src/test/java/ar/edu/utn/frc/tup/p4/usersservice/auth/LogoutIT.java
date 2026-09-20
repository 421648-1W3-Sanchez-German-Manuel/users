package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-02: logout deletes session:{userId}, cutting off access immediately. */
class LogoutIT extends AbstractIntegrationTest {

    /**
     * MySQL and Redis are shared singletons with NO cleanup between classes
     * (AbstractIntegrationTest). With fixed addresses, any other batch that
     * uses one of them, or a repeated run in the same JVM, causes a duplicate
     * key 409 in the fixture INSERT that looks like a failure in the code under
     * test.
     */
    private static final String SUF = "-" + UUID.randomUUID() + "@utn.edu.ar";

    @Autowired AuthService auth;
    @Autowired TokenStore store;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void logoutDeletesTheSessionKey() {
        User user = User.create("A", "A", "out" + SUF,
                encoder.encode("validpassword1"), Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
        UUID id = repo.saveAndFlush(user).getId();

        var tokens = auth.issueTokenPair(id);
        assertThat(store.findSessionId(id)).isPresent();

        auth.logout(id, tokens.refreshToken());

        // Without the key, the Gateway returns 401 "session closed" (DEC-01),
        // without waiting the ~10 min of exp.
        assertThat(store.findSessionId(id)).isEmpty();
        assertThatThrownBy(() -> auth.refresh(tokens.refreshToken())).isInstanceOf(ApiException.class);
    }
}
