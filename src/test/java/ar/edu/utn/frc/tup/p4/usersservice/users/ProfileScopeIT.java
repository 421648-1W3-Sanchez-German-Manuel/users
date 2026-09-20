package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scope of a service token is validated when it is issued, signed into the
 * token, checked against the destination by the gateway's ServiceAudienceFilter
 * and turned into a GrantedAuthority by GatewayIdentityFilter — and until now
 * NO endpoint ever read it. {@code grep hasAuthority src/main} returned nothing.
 *
 * <p>That was harmless only by accident: ScopeCatalog has exactly one issuable
 * scope, so every service token in existence carried the one this endpoint
 * wants. The check has to exist BEFORE the second scope does, not after.
 *
 * <p>RANDOM_PORT and a real socket, like SessionCookieIT: @PreAuthorize needs
 * the whole filter chain, and what is under test is precisely what the identity
 * headers produce in the SecurityContext.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileScopeIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired UserRepository repo;

    private UUID target;
    private UUID caller;

    @BeforeEach
    void seedUsers() {
        target = persistActive("Ana", "Perez", "anaperez");
        // A REAL caller: AccountGateInterceptor looks the person up by
        // X-User-Id on every authenticated request, so an invented UUID gets
        // invalid-credentials and the test would measure the gate, not the scope.
        caller = persistActive("Beto", "Quiroga", "betoq");
    }

    private UUID persistActive(String firstNames, String lastNames, String github) {
        User u = User.create(firstNames, lastNames, "scope-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding(true);
        u.clearFirstLogin();
        u.mirrorGithubUsername(github);
        return repo.saveAndFlush(u).getId();
    }

    private HttpResponse<String> asService(String scopes) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder()
                .header(IdentityHeaders.PRINCIPAL_TYPE, "service")
                .header(IdentityHeaders.SERVICE_ID, "courses-service")
                .header(IdentityHeaders.SERVICE_SCOPES, scopes));
    }

    private HttpResponse<String> asPerson() throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder()
                .header(IdentityHeaders.PRINCIPAL_TYPE, "user")
                .header(IdentityHeaders.USER_ID, caller.toString())
                .header(IdentityHeaders.USER_ROLES, "STUDENT"));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                builder.uri(URI.create("http://localhost:" + port + "/api/users/profile/" + target))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void a_service_token_WITH_the_scope_reads_the_profile() throws Exception {
        assertThat(asService("MS,users.profile.read").statusCode()).isEqualTo(200);
    }

    @Test
    void a_service_token_WITHOUT_the_scope_is_denied() throws Exception {
        HttpResponse<String> response = asService("MS,cursos.inscripciones.read");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("access-denied");
    }

    /**
     * A service token carrying ONLY the MS role is still a service token: the
     * role says "I am a microservice", the scope says what it may ask for. The
     * two are different questions and MS must not answer the second one.
     */
    @Test
    void the_MS_role_alone_is_not_enough() throws Exception {
        assertThat(asService("MS").statusCode()).isEqualTo(403);
    }

    /**
     * The other half: this endpoint is "what any classmate can see" (DEC-36),
     * so a person reaches it with no scope at all — people do not have scopes.
     * Adding the scope check must not lock them out.
     */
    @Test
    void a_person_still_reads_the_profile_without_any_scope() throws Exception {
        assertThat(asPerson().statusCode()).isEqualTo(200);
    }
}
