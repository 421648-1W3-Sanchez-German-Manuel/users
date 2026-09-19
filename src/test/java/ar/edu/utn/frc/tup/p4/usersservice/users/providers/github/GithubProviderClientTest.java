package ar.edu.utn.frc.tup.p4.usersservice.users.providers.github;

import ar.edu.utn.frc.tup.p4.usersservice.config.GitProviderProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Etapa 3 §4.5 #1–#4 + #6: the real adapter against a mocked HTTP server —
 * no internet, no new dependencies. The server stands in for github.com and
 * api.github.com on localhost.
 */
class GithubProviderClientTest {

    private static final String SECRET = "s3cr3t-that-must-never-leak";

    private HttpServer server;
    private String base;
    private GithubProviderClient client;

    private final AtomicReference<String> tokenAccept = new AtomicReference<>();
    private final AtomicReference<String> tokenBody = new AtomicReference<>();
    private final AtomicReference<String> userAuthorization = new AtomicReference<>();
    private final AtomicInteger tokenStatus = new AtomicInteger(200);
    private final AtomicInteger userStatus = new AtomicInteger(200);
    private final AtomicReference<String> userBody = new AtomicReference<>(
            "{\"id\": 42, \"login\": \"octocat\", \"avatar_url\": \"https://avatars/x\", \"name\": \"O\"}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/login/oauth/access_token", ex -> {
            tokenAccept.set(firstHeader(ex.getRequestHeaders().get("Accept")));
            tokenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, tokenStatus.get(), "{\"access_token\": \"tok-1\", \"token_type\": \"bearer\", \"scope\": \"\"}");
        });
        server.createContext("/user", ex -> {
            userAuthorization.set(firstHeader(ex.getRequestHeaders().get("Authorization")));
            respond(ex, userStatus.get(), userBody.get());
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
        GitProviderProperties props = new GitProviderProperties(true, "cid-1", SECRET,
                "http://front/vinculacion/callback", "", Duration.ofMinutes(5));
        client = new GithubProviderClient(props, base, base);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String firstHeader(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** #1: the authorization URL carries client_id, redirect_uri, scope and state. */
    @Test
    void authorization_url_carries_client_id_redirect_scope_and_state() {
        var params = UriComponentsBuilder.fromUriString(client.authorizationUrl("st-1").toString())
                .build().getQueryParams();

        assertThat(params.getFirst("client_id")).isEqualTo("cid-1");
        assertThat(params.getFirst("redirect_uri")).isEqualTo("http://front/vinculacion/callback");
        assertThat(params.getFirst("scope")).isEqualTo("");
        assertThat(params.getFirst("state")).isEqualTo("st-1");
    }

    /** #2: the exchange asks for JSON and parses the token reply. */
    @Test
    void exchange_sends_json_accept_and_parses_token() {
        var identity = client.exchange("code-1");

        assertThat(tokenAccept.get()).contains("application/json");
        assertThat(tokenBody.get()).contains("\"code\":\"code-1\"");
        assertThat(userAuthorization.get()).isEqualTo("Bearer tok-1");
        assertThat(identity.externalUserId()).isEqualTo("42");
        assertThat(identity.username()).isEqualTo("octocat");
    }

    /** #3: the key is the numeric id, never the login (DEC-GL-01). */
    @Test
    void identity_is_keyed_by_numeric_id_not_login() {
        userBody.set("{\"id\": 987654, \"login\": \"recycled-handle\"}");

        var identity = client.exchange("code-1");

        assertThat(identity.externalUserId()).isEqualTo("987654");
        assertThat(identity.username()).isEqualTo("recycled-handle");
    }

    /** #4: provider 5xx on either call → 502 provider-unavailable, never a bare 500. */
    @Test
    void provider_5xx_becomes_502() {
        userStatus.set(500);

        assertThatThrownBy(() -> client.exchange("code-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(api.getType().toString()).endsWith("provider-unavailable");
                });
    }

    @Test
    void token_endpoint_5xx_becomes_502() {
        tokenStatus.set(503);

        assertThatThrownBy(() -> client.exchange("code-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /** #4 (timeout/refused connection variant): unreachable provider → 502. */
    @Test
    void unreachable_provider_becomes_502() {
        GitProviderProperties props = new GitProviderProperties(true, "cid-1", SECRET,
                "http://front/vinculacion/callback", "", Duration.ofMinutes(5));
        GithubProviderClient dead = new GithubProviderClient(props, "http://localhost:1", "http://localhost:1");

        assertThatThrownBy(() -> dead.exchange("code-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /** #6: the client_secret appears in no error message. */
    @Test
    void client_secret_never_in_error_messages() {
        userStatus.set(500);

        assertThatThrownBy(() -> client.exchange("code-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getMessage()).doesNotContain(SECRET);
                    assertThat(api.toString()).doesNotContain(SECRET);
                    assertThat(Map.of("detail", api.getMessage())).doesNotContainValue(SECRET);
                });
    }
}
