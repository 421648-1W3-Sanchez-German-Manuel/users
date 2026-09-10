package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.entities.ServiceClient;
import ar.edu.utn.frc.tup.p4.usersservice.auth.repositories.ServiceClientRepository;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.ServiceClientService;
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

    @Test
    void correctAudienceIssuesServiceToken() throws Exception {
        String jwt = service.emitirServicio(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", "users-service");

        var claims = SignedJWT.parse(jwt).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("users-service");
        assertThat(claims.getStringListClaim("roles")).containsExactly("MS");
        assertThat(claims.getStringClaim("type")).isEqualTo("service");
        assertThat(claims.getIssuer()).isEqualTo("users-service");
    }

    @Test
    void missingAudienceReturns400WithoutIssuing() {
        assertThatThrownBy(() -> service.emitirServicio(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void audienceNotDerivedFromScopeReturns400() {
        assertThatThrownBy(() -> service.emitirServicio(
                CLIENT_ID, CLIENT_SECRET, "users.profile.read", "courses-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void nonIssuableScopeReturns400() {
        assertThatThrownBy(() -> service.emitirServicio(
                CLIENT_ID, CLIENT_SECRET, "mailing.debug.read", "mailing-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void scopeOutsideClientAllowlistReturns400() {
        String clientId = "other-service-" + UUID.randomUUID();
        repository.saveAndFlush(ServiceClient.create(
                clientId, encoder.encode("another-secret"), "Other", Set.of()));

        assertThatThrownBy(() -> service.emitirServicio(
                clientId, "another-secret", "users.profile.read", "users-service"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
    }

    @Test
    void wrongSecretAndUnknownClientReturnSameError() {
        String wrongSecret = capture(() -> service.emitirServicio(
                CLIENT_ID, "wrong", "users.profile.read", "users-service"));
        String unknownClient = capture(() -> service.emitirServicio(
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
