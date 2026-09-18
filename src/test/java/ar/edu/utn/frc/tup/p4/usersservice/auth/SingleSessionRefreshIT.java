package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.TokenResponse;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-22 - the intuition that "if the refresh does not rotate the sid, an old
 * old one revives the session" is INVERTED: rotating is what allows reviving it.
 * Of the four combinations (new/same sid x writes/does not write Redis) only
 * one works: same sid + does not write.
 */
class SingleSessionRefreshIT extends AbstractIntegrationTest {

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

    private UUID createUser(String email) {
        User user = User.create("A", "A", email, encoder.encode("validpassword1"), Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(user).getId();
    }

    @Test
    void refreshDoesNotGenerateANewSessionIdOrWriteToRedis() throws Exception {
        UUID id = createUser("ref1" + SUF);
        TokenResponse tokens1 = auth.issueTokenPair(id);
        String sid = store.findSessionId(id).orElseThrow();

        TokenResponse tokens2 = auth.refresh(tokens1.refreshToken());

        assertThat(SignedJWT.parse(tokens2.accessToken()).getJWTClaimsSet().getStringClaim("sid"))
                .isEqualTo(sid);
        assertThat(store.findSessionId(id)).contains(sid);   // the key did not change
    }

    @Test
    void supersededDeviceReceives401AndItsFamilyIsRevoked() {
        // A is logged in, B logs in, and A attempts to refresh.
        UUID id = createUser("ref2" + SUF);
        TokenResponse fromA = auth.issueTokenPair(id);
        TokenResponse fromB = auth.issueTokenPair(id);   // overwrites the session

        assertThatThrownBy(() -> auth.refresh(fromA.refreshToken()))
                .isInstanceOf(ApiException.class);

        // B's refresh token still works.
        assertThat(auth.refresh(fromB.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheEntireFamily() {
        UUID id = createUser("ref3" + SUF);
        TokenResponse tokens1 = auth.issueTokenPair(id);
        TokenResponse tokens2 = auth.refresh(tokens1.refreshToken());   // tokens1 is now rotated

        // A theft signal: somebody else holds the old refresh token.
        assertThatThrownBy(() -> auth.refresh(tokens1.refreshToken())).isInstanceOf(ApiException.class);
        // And the new one dies too: the whole family was revoked.
        assertThatThrownBy(() -> auth.refresh(tokens2.refreshToken())).isInstanceOf(ApiException.class);
    }

    @Test
    void refreshReloadsTheStatusFromTheDatabase() throws Exception {
        // DEC-23: this is what makes refreshing the propagation mechanism
        // fast when the account gains access.
        User user = User.create("A", "A", "ref4" + SUF,
                encoder.encode("validpassword1"), Role.STUDENT, "v1");
        user.activate();                       // PENDING_COURSE
        repo.saveAndFlush(user);

        TokenResponse tokens1 = auth.issueTokenPair(user.getId());
        assertThat(SignedJWT.parse(tokens1.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("PENDING_COURSE");

        user.activateAfterCourseValidation();
        repo.saveAndFlush(user);

        TokenResponse tokens2 = auth.refresh(tokens1.refreshToken());
        assertThat(SignedJWT.parse(tokens2.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("ACTIVE");      // without logging in again
    }
}
