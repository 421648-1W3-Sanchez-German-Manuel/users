package ar.edu.utn.frc.tup.p4.usersservice.auth.tokens;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenContractTest {

    private static final List<String> REQUIRED_PERSON_CLAIMS =
            List.of("iss", "sub", "roles", "type", "jti", "sid", "est", "pwd", "onb", "iat", "exp");

    private static final List<String> REQUIRED_SERVICE_CLAIMS =
            List.of("iss", "sub", "roles", "type", "aud", "scope", "jti", "iat", "exp");

    private JWTClaimsSet person() {
        return TokenClaims.paraPersona(UUID.randomUUID(), List.of(Role.STUDENT), "sid-1",
                        AccountStatus.ACTIVE, false, false)
                .build()
                .aClaimsSet("users-service", Duration.ofMinutes(10));
    }

    private JWTClaimsSet service() {
        return TokenClaims.paraServicio(
                        "courses-service", "users-service", Set.of("users.profile.read"))
                .build()
                .aClaimsSet("users-service", Duration.ofMinutes(5));
    }

    @Test
    void personTokenContainsEveryRequiredClaim() {
        JWTClaimsSet claims = person();

        for (String claim : REQUIRED_PERSON_CLAIMS) {
            assertThat(claims.getClaim(claim))
                    .as("person token is missing claim '%s'", claim)
                    .isNotNull();
        }
    }

    @Test
    void serviceTokenContainsEveryRequiredClaim() {
        JWTClaimsSet claims = service();

        for (String claim : REQUIRED_SERVICE_CLAIMS) {
            assertThat(claims.getClaim(claim))
                    .as("service token is missing claim '%s'", claim)
                    .isNotNull();
        }
    }

    @Test
    void issuerIsAlwaysUsersService() {
        assertThat(person().getIssuer()).isEqualTo("users-service");
        assertThat(service().getIssuer()).isEqualTo("users-service");
    }

    @Test
    void serviceTokenDoesNotContainPersonSessionOrAccountClaims() {
        JWTClaimsSet claims = service();

        assertThat(claims.getClaim("sid")).isNull();
        assertThat(claims.getClaim("est")).isNull();
        assertThat(claims.getClaim("pwd")).isNull();
        assertThat(claims.getClaim("onb")).isNull();
    }

    @Test
    void msRoleOnlyAppearsInServiceTokens() throws Exception {
        assertThat(service().getStringListClaim("roles")).containsExactly("MS");
        assertThat(person().getStringListClaim("roles")).doesNotContain("MS");
    }

    @Test
    void onBehalfOfIsTheOnlyOptionalServiceClaim() {
        UUID actor = UUID.randomUUID();
        JWTClaimsSet withActor = TokenClaims
                .paraServicio("courses-service", "users-service", Set.of("users.profile.read"))
                .conOnBehalfOf(actor)
                .build()
                .aClaimsSet("users-service", Duration.ofMinutes(5));

        assertThat(withActor.getClaim("on_behalf_of")).isEqualTo(actor.toString());
        assertThat(service().getClaim("on_behalf_of")).isNull();
    }

    @Test
    void expirationMatchesRequestedLifetime() {
        JWTClaimsSet claims = person();
        long lifetime = claims.getExpirationTime().toInstant().getEpochSecond()
                - claims.getIssueTime().toInstant().getEpochSecond();

        assertThat(lifetime).isEqualTo(600);
    }
}
