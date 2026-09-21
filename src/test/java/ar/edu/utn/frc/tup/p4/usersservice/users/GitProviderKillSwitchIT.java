package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-GL-05: {@code enabled=false} is a real kill switch. Credentials left
 * behind in the environment must not keep the endpoints alive — turning the
 * feature off is exactly what an operator expects that flag to do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitProviderKillSwitchIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void credentialsButDisabled(DynamicPropertyRegistry registry) {
        registry.add("users.git-providers.github.enabled", () -> "false");
        registry.add("users.git-providers.github.client-id", () -> "cid-test");
        registry.add("users.git-providers.github.client-secret", () -> "secret-test");
        registry.add("users.git-providers.github.redirect-uri", () -> "http://front/vinculacion/callback");
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired(required = false) GitProviderClient github;

    @Test
    void credentials_alone_do_not_keep_linking_alive() throws Exception {
        assertThat(github).as("disabled wins over the credentials").isNull();

        User u = User.create("Ada", "L", "gitkill-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        users.saveAndFlush(u);

        HttpResponse<String> res = CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/users/me/git-links/GITHUB/start"))
                .header("X-Principal-Type", "user")
                .header("X-User-Id", u.getId().toString())
                .header("X-User-Roles", "STUDENT")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("provider-not-supported");
    }
}
