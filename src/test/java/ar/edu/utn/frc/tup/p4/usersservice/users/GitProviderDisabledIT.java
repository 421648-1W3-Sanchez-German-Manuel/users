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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 3 §4.5 #5 (DEC-GL-05): with no {@code client-id} the adapter does not
 * register and the endpoint answers {@code provider-not-supported} — while the
 * app boots normally. No fake provider is imported here on purpose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitProviderDisabledIT extends AbstractIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired(required = false) GitProviderClient github;

    @Test
    void without_client_id_the_adapter_is_absent_and_start_is_400() throws Exception {
        assertThat(github).as("no adapter without credentials (DEC-GL-05)").isNull();

        User u = User.create("Ada", "L", "gitdis-" + UUID.randomUUID() + "@utn.edu.ar",
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
