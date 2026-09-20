package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.entities.ServiceClient;
import ar.edu.utn.frc.tup.p4.usersservice.auth.repositories.ServiceClientRepository;
import ar.edu.utn.frc.tup.p4.usersservice.auth.controllers.TokenController;
import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.ClientCredentialsRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.ServiceClientService;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientCredentialsIT extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "courses-service";
    private static final String CLIENT_SECRET = "a-long-service-secret";

    @Autowired
    ServiceClientService service;

    @Autowired
    ServiceClientRepository repository;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TokenController controller;

    @Autowired
    JwtProperties jwtProperties;

    @BeforeEach
    void createClient() {
        if (repository.findByClientIdAndDeletedAtIsNull(CLIENT_ID).isEmpty()) {
            repository.saveAndFlush(ServiceClient.create(
                    CLIENT_ID,
                    encoder.encode(CLIENT_SECRET),
                    "Courses",
                    Set.of("users.profile.read")));
        }
    }

    /**
     * The `expiresIn` the endpoint advertises has to come from the same config
     * as the `exp` it signs. With the value hardcoded they agreed only by
     * coincidence, and the first change to users.jwt.service-ttl would make the
     * response lie — the client caches on the word of this field and starts
     * sending a token that is already expired.
     */
    @Test
    void the_advertised_expiresIn_matches_the_signed_exp() throws Exception {
        String jwt = service.issueServiceToken(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", "users-service");
        var claims = SignedJWT.parse(jwt).getJWTClaimsSet();

        long signedLifetime = claims.getExpirationTime().toInstant().getEpochSecond()
                - claims.getIssueTime().toInstant().getEpochSecond();

        assertThat(signedLifetime).isEqualTo(jwtProperties.serviceTtl().toSeconds());
        assertThat((long) controller.token(new ClientCredentialsRequest(
                        CLIENT_ID, CLIENT_SECRET, "client_credentials",
                        "users.profile.read", "users-service")).get("expiresIn"))
                .isEqualTo(signedLifetime);
    }

    /**
     * Non-negotiable 5 is about not building enumeration oracles, and an
     * unknown clientId used to answer ~100 ms faster than a known one: the
     * BCrypt only ran when the row existed. Same response, different duration,
     * and the duration is the part an attacker measures.
     *
     * The assertion is deliberately loose — a wall clock on a shared CI box is
     * not a stopwatch. What it catches is the REGRESSION that matters: going
     * back to Optional.filter(...) makes the unknown branch skip BCrypt
     * entirely and drop to ~1 ms, which is an order of magnitude below the
     * floor asserted here, not a few milliseconds.
     */
    @Test
    void an_unknown_clientId_costs_the_same_BCrypt_as_a_known_one() {
        long known = millisOf(() -> service.issueServiceToken(
                CLIENT_ID, "the-wrong-secret", "users.profile.read", "users-service"));
        long unknown = millisOf(() -> service.issueServiceToken(
                "no-such-client-" + UUID.randomUUID(), CLIENT_SECRET,
                "users.profile.read", "users-service"));

        // The test profile uses a cheap encoder, so this is not "about 100 ms":
        // it is "the same order of magnitude as a real comparison".
        assertThat(unknown).isGreaterThanOrEqualTo(known / 4);
    }

    private long millisOf(Runnable rejected) {
        long start = System.nanoTime();
        assertThatThrownBy(rejected::run).isInstanceOf(ApiException.class);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void correctAudienceIssuesServiceToken() throws Exception {
        String jwt = service.issueServiceToken(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", "users-service");

        var claims = SignedJWT.parse(jwt).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("users-service");
        assertThat(claims.getStringListClaim("roles")).containsExactly("MS");
        assertThat(claims.getStringClaim("type")).isEqualTo("service");
        assertThat(claims.getIssuer()).isEqualTo("users-service");
    }

    @Test
    void missingAudienceReturns400WithoutIssuing() {
        assertThatThrownBy(() -> service.issueServiceToken(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void audienceNotDerivedFromScopeReturns400() {
        assertThatThrownBy(() -> service.issueServiceToken(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", "courses-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void nonIssuableScopeReturns400() {
        assertThatThrownBy(() -> service.issueServiceToken(
                CLIENT_ID, CLIENT_SECRET, "mailing.debug.read", "mailing-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void scopeOutsideClientAllowlistReturns400() {
        String clientId = "other-service-" + UUID.randomUUID();
        repository.saveAndFlush(ServiceClient.create(
                clientId, encoder.encode("another-secret"), "Other", Set.of()));

        assertThatThrownBy(() -> service.issueServiceToken(
                clientId, "another-secret", "users.profile.read", "users-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void wrongSecretAndUnknownClientReturnSameError() {
        String wrongSecret = capture(() -> service.issueServiceToken(
                CLIENT_ID, "wrong", "users.profile.read", "users-service"));
        String unknownClient = capture(() -> service.issueServiceToken(
                "unknown-service", "wrong", "users.profile.read", "users-service"));

        assertThat(wrongSecret).isEqualTo(unknownClient);
    }

    private String capture(Runnable action) {
        try {
            action.run();
            return "did-not-fail";
        } catch (ApiException exception) {
            return exception.getMessage();
        }
    }
}
