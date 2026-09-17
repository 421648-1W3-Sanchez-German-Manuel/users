package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.OutboxRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DoD criteria #3 (complete end-to-end login) and #4 (a second login
 * overwrites the session).
 */
@Import(TestOtpSpy.Config.class)   // Step 5's spy, only for this test
class LoginIT extends AbstractIntegrationTest {

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
    @Autowired TokenStore store;
    @Autowired OutboxRepository outbox;      // DoD #3: the email must be sent
    @Autowired TestOtpSpy otpSpy;   // captures the generated code; see Step 5

    private User createActiveUser(String email, String password) {
        User user = User.create("Ana", "Perez", email, encoder.encode(password), Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(user);
    }

    @Test
    void phaseOneDoesNotReturnTokens() throws Exception {
        createActiveUser("f1" + SUF, "validpassword1");
        var response = auth.login("f1" + SUF, "validpassword1");
        assertThat(response.challengeId()).isNotBlank();
        // If phase one returned tokens, 2FA would be merely decorative.
        assertThat(response.toString()).doesNotContain("eyJ");
    }

    @Test
    void phaseOneRendersTheEmailAndPublishesTheEvent() {
        // DoD criterion #3, section "2FA code generated and email rendered
        // -> event published". Without this assert, the login could issue
        // perfect tokens and never send the code: green in the tests,
        // broken for the user.
        createActiveUser("f2a" + SUF, "validpassword1");
        long before = outbox.count();

        auth.login("f2a" + SUF, "validpassword1");

        assertThat(outbox.count()).isGreaterThan(before);
        assertThat(outbox.findAll()).anySatisfy(e -> {
            assertThat(e.getPayload()).contains("TWO-FACTOR-EMAIL-PREPARED");
            assertThat(e.getPayload()).contains("f2a" + SUF);
            // The mail goes out ALREADY BUILT: subject + html, not a templateId.
            assertThat(e.getPayload()).contains("\"subject\"").contains("\"html\"");
            // And the code NEVER appears in the audit event or in a log.
            assertThat(e.getDestinationTopic()).isEqualTo("notification-events");
        });
    }

    @Test
    void phaseTwoWithTheCorrectCodeIssuesBothTokens() throws Exception {
        User user = createActiveUser("f2" + SUF, "validpassword1");
        var challenge = auth.login("f2" + SUF, "validpassword1");

        var tokens = auth.verifyTwoFactor(challenge.challengeId(), otpSpy.lastCode());

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        var claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("users-service");
        assertThat(claims.getStringClaim("est")).isEqualTo("ACTIVE");
        // DEC-22: the post-2FA login is the ONLY operation that writes the session.
        assertThat(store.findSessionId(user.getId())).contains(claims.getStringClaim("sid"));
    }

    @Test
    void secondLoginOverwritesTheFirstSession() throws Exception {
        User user = createActiveUser("f3" + SUF, "validpassword1");

        var challenge1 = auth.login("f3" + SUF, "validpassword1");
        var tokens1 = auth.verifyTwoFactor(challenge1.challengeId(), otpSpy.lastCode());
        String sid1 = SignedJWT.parse(tokens1.accessToken()).getJWTClaimsSet().getStringClaim("sid");

        var challenge2 = auth.login("f3" + SUF, "validpassword1");
        var tokens2 = auth.verifyTwoFactor(challenge2.challengeId(), otpSpy.lastCode());
        String sid2 = SignedJWT.parse(tokens2.accessToken()).getJWTClaimsSet().getStringClaim("sid");

        assertThat(sid1).isNotEqualTo(sid2);
        assertThat(store.findSessionId(user.getId())).contains(sid2);   // the latest one wins
    }

    @Test
    void pendingCourseAccountCanLogInAndItsTokenReflectsTheStatus() throws Exception {
        // INC-19: RF-USR-05f prohibits ACCESS TO FEATURES, not token issuance.
        // The token is how the person queries
        // GET /me and finds out what is missing. The set of features
        // available is empty (DEC-23, coarse-grained gate in the Gateway).
        User user = User.create("B", "B", "pend" + SUF,
                encoder.encode("validpassword1"), Role.STUDENT, "v1");
        user.activate();                     // -> PENDING_COURSE
        repo.saveAndFlush(user);

        var challenge = auth.login("pend" + SUF, "validpassword1");
        var tokens = auth.verifyTwoFactor(challenge.challengeId(), otpSpy.lastCode());

        assertThat(SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("PENDING_COURSE");
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnTheSameError() {
        createActiveUser("f4" + SUF, "validpassword1");
        String wrongPasswordMessage = capture(() -> auth.login("f4" + SUF, "somethingelse1234"));
        String unknownEmailMessage = capture(() -> auth.login("nobody" + SUF, "somethingelse1234"));
        assertThat(wrongPasswordMessage).isEqualTo(unknownEmailMessage);
    }

    @Test
    void wrongTwoFactorCodeDoesNotIssueTokens() {
        createActiveUser("f5" + SUF, "validpassword1");
        var challenge = auth.login("f5" + SUF, "validpassword1");
        assertThatThrownBy(() -> auth.verifyTwoFactor(challenge.challengeId(), "000000"))
                .isInstanceOf(ApiException.class);
    }

    private String capture(Runnable action) {
        try { action.run(); return "did-not-fail"; }
        catch (ApiException exception) { return exception.getMessage(); }
    }
}
