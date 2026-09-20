package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 2 (§3.5): the full start → callback flow against HTTP, with a fake
 * provider — no internet. The fake stands in for the GithubProviderClient of
 * Etapa 3; the contract it speaks (GitProviderClient) is frozen by P1.
 */
@Import(GitProviderLinkIT.FakeProvider.Config.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitProviderLinkIT extends AbstractIntegrationTest {

    /** Fake adapter: canned identity, toggleable exchange failure (§3.5 #11). */
    static class FakeProvider implements GitProviderClient {
        final AtomicBoolean failExchange = new AtomicBoolean(false);

        @Override
        public GitProvider provider() { return GitProvider.GITHUB; }

        @Override
        public URI authorizationUrl(String state) {
            return URI.create("https://github.test/login/oauth/authorize?state=" + state);
        }

        @Override
        public GitProviderIdentity exchange(String authorizationCode) {
            if (failExchange.get()) throw ApiException.providerUnavailable();
            return new GitProviderIdentity("ext-" + authorizationCode, "octocat");
        }

        @Configuration
        static class Config {
            @Bean
            FakeProvider fakeProvider() { return new FakeProvider(); }
        }
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired EphemeralTokenService ephemeral;
    @Autowired FakeProvider fake;

    private User activeUser(String tag) {
        User u = User.create("Ada", "Lovelace", "gitlink-" + tag + "-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return users.saveAndFlush(u);
    }

    /** Fully inside: passes all three gates (for DELETE / GET, which are not gate exits). */
    private User insideUser(String tag) {
        User u = activeUser(tag);
        u.changePassword("$2a$12$h");
        u.completeOnboarding(true);
        u.clearFirstLogin();
        return users.saveAndFlush(u);
    }

    private HttpResponse<String> post(String path, UUID userId, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Principal-Type", "user")
                .header("X-User-Id", userId.toString())
                .header("X-User-Roles", "STUDENT");
        if (body == null) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            b.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, UUID userId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Principal-Type", "user")
                .header("X-User-Id", userId.toString())
                .header("X-User-Roles", "STUDENT")
                .GET().build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, UUID userId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Principal-Type", "user")
                .header("X-User-Id", userId.toString())
                .header("X-User-Roles", "STUDENT")
                .DELETE().build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String stateFrom(String authorizationUrl) {
        String prefix = "state=";
        int i = authorizationUrl.indexOf(prefix);
        assertThat(i).as("authorizationUrl carries the state: %s", authorizationUrl).isNotNegative();
        String state = authorizationUrl.substring(i + prefix.length());
        int amp = state.indexOf('&');
        return amp < 0 ? state : state.substring(0, amp);
    }

    private String startState(UUID userId) throws Exception {
        var res = post("/api/users/me/git-links/GITHUB/start", userId, null);
        assertThat(res.statusCode()).isEqualTo(200);
        return stateFrom(JSON.readTree(res.body()).get("authorizationUrl").asText());
    }

    private HttpResponse<String> callback(UUID userId, String code, String state) throws Exception {
        return post("/api/users/me/git-links/GITHUB/callback", userId,
                "{\"code\":\"" + code + "\",\"state\":\"" + state + "\"}");
    }

    /** #1: start returns the adapter URL and leaves the state stored. */
    @Test
    void start_returns_adapter_url_and_stores_state() throws Exception {
        User u = activeUser("start");
        var res = post("/api/users/me/git-links/GITHUB/start", u.getId(), null);

        assertThat(res.statusCode()).isEqualTo(200);
        String url = JSON.readTree(res.body()).get("authorizationUrl").asText();
        assertThat(url).startsWith("https://github.test/");
        String state = stateFrom(url);
        assertThat(ephemeral.find("gitlink:" + state))
                .hasValue(u.getId() + ":GITHUB"); // DEC-GL-09
    }

    /** #2: the full start → callback flow persists the row. */
    @Test
    void full_flow_persists_the_link() throws Exception {
        User u = insideUser("full");
        String state = startState(u.getId());

        var res = callback(u.getId(), "code-" + UUID.randomUUID(), state);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(res.body());
        assertThat(body.get("provider").asText()).isEqualTo("GITHUB");
        assertThat(body.get("username").asText()).isEqualTo("octocat");
        JsonNode listed = JSON.readTree(get("/api/users/me/git-links", u.getId()).body());
        assertThat(listed.size()).isOne();
        assertThat(listed.get(0).get("username").asText()).isEqualTo("octocat");
    }

    /** #3: unknown, used or expired state → 400 invalid-link-state, never 401. */
    @Test
    void unknown_state_is_400_never_401() throws Exception {
        User u = activeUser("unknown");
        var res = callback(u.getId(), "code-x", "no-such-state");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid-link-state");
    }

    @Test
    void expired_state_is_400() throws Exception {
        User u = activeUser("expired");
        String state = "st-" + UUID.randomUUID();
        ephemeral.save("gitlink:" + state, u.getId() + ":GITHUB", Duration.ofMillis(50));
        Thread.sleep(200);

        var res = callback(u.getId(), "code-x", state);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid-link-state");
    }

    /** #4: state saved for another provider than the callback → 400. */
    @Test
    void state_of_another_provider_is_400() throws Exception {
        User u = activeUser("otherprov");
        String state = "st-" + UUID.randomUUID();
        ephemeral.save("gitlink:" + state, u.getId() + ":BITBUCKET", Duration.ofMinutes(5));

        var res = callback(u.getId(), "code-x", state);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid-link-state");
    }

    /** #4b: state of one user + bearer of another → 409, never 401 (DEC-GL-16). */
    @Test
    void state_of_another_user_is_409_never_401() throws Exception {
        User a = activeUser("userA");
        User b = activeUser("userB");
        String state = startState(a.getId());

        var res = callback(b.getId(), "code-x", state);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("link-user-mismatch");
    }

    /** #5: the state cannot be used twice. */
    @Test
    void state_is_single_use() throws Exception {
        User u = activeUser("single");
        String code = "code-" + UUID.randomUUID();
        String state = startState(u.getId());

        assertThat(callback(u.getId(), code, state).statusCode()).isEqualTo(200);
        var retry = callback(u.getId(), code, state);

        assertThat(retry.statusCode()).isEqualTo(400);
        assertThat(retry.body()).contains("invalid-link-state");
    }

    /** #6: unknown {provider} → 400 provider-not-supported, in problem+json. */
    @Test
    void unknown_provider_is_400_with_type() throws Exception {
        User u = activeUser("unknownprov");
        var res = post("/api/users/me/git-links/GITLAB/start", u.getId(), null);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("provider-not-supported");
        assertThat(res.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    /** #7: start with an active link → 409 immediately, no state (DEC-GL-18). */
    @Test
    void start_with_active_link_is_409() throws Exception {
        User u = activeUser("alreadystart");
        String state = startState(u.getId());
        assertThat(callback(u.getId(), "code-" + UUID.randomUUID(), state).statusCode()).isEqualTo(200);

        var res = post("/api/users/me/git-links/GITHUB/start", u.getId(), null);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("provider-already-linked");
    }

    /** #8: DELETE soft-deletes, clears the mirror, and a later start works. */
    @Test
    void delete_unlinks_and_allows_relink() throws Exception {
        User u = insideUser("del");
        String state = startState(u.getId());
        assertThat(callback(u.getId(), "code-" + UUID.randomUUID(), state).statusCode()).isEqualTo(200);

        var del = delete("/api/users/me/git-links/GITHUB", u.getId());
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(users.findById(u.getId())).isPresent();
        assertThat(users.findById(u.getId()).orElseThrow().getGithubUsername()).isNull();

        var relink = post("/api/users/me/git-links/GITHUB/start", u.getId(), null);
        assertThat(relink.statusCode()).isEqualTo(200);
    }

    /** #9: DELETE without an active link → 404. */
    @Test
    void delete_without_link_is_404() throws Exception {
        User u = insideUser("nolink");

        var res = delete("/api/users/me/git-links/GITHUB", u.getId());

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("provider-not-linked");
    }

    /** #10: GET lists the active link; empty when none (or freshly unlinked). */
    @Test
    void get_lists_active_links_then_empty_after_unlink() throws Exception {
        User u = insideUser("list");

        assertThat(JSON.readTree(get("/api/users/me/git-links", u.getId()).body()).size()).isZero();

        String state = startState(u.getId());
        assertThat(callback(u.getId(), "code-" + UUID.randomUUID(), state).statusCode()).isEqualTo(200);

        JsonNode listed = JSON.readTree(get("/api/users/me/git-links", u.getId()).body());
        assertThat(listed.size()).isOne();
        assertThat(listed.get(0).get("provider").asText()).isEqualTo("GITHUB");
        assertThat(listed.get(0).get("username").asText()).isEqualTo("octocat");
        assertThat(listed.get(0).get("linkedAt").asText()).isNotBlank();

        assertThat(delete("/api/users/me/git-links/GITHUB", u.getId()).statusCode()).isEqualTo(204);
        assertThat(JSON.readTree(get("/api/users/me/git-links", u.getId()).body()).size()).isZero();
    }

    /** #11: adapter failure still burns the state (DEC-GL-22). */
    @Test
    void adapter_failure_burns_the_state() throws Exception {
        User u = activeUser("burn");
        String code = "code-" + UUID.randomUUID();
        String state = startState(u.getId());
        fake.failExchange.set(true);
        try {
            var res = callback(u.getId(), code, state);
            assertThat(res.statusCode()).isEqualTo(502);
            assertThat(res.body()).contains("provider-unavailable");
        } finally {
            fake.failExchange.set(false);
        }

        var retry = callback(u.getId(), code, state);
        assertThat(retry.statusCode()).isEqualTo(400);
        assertThat(retry.body()).contains("invalid-link-state");
    }
}
