package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 2 §3.5 #12 (non-negotiable 7): no log line of the flow may contain
 * the {@code code}, the {@code state} or a provider token. A token in a log
 * file is a stolen token.
 */
@Import(GitProviderLinkLoggingTest.QuietProvider.Config.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitProviderLinkLoggingTest extends AbstractIntegrationTest {

    static class QuietProvider implements GitProviderClient {
        @Override
        public GitProvider provider() { return GitProvider.GITHUB; }

        @Override
        public URI authorizationUrl(String state) {
            return URI.create("https://github.test/login/oauth/authorize?state=" + state);
        }

        @Override
        public GitProviderIdentity exchange(String authorizationCode) {
            return new GitProviderIdentity("ext-" + authorizationCode, "octocat");
        }

        @Configuration
        static class Config {
            @Bean
            QuietProvider quietProvider() { return new QuietProvider(); }
        }
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired UserRepository users;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(appender);
    }

    @Test
    void flow_logs_neither_code_nor_state() throws Exception {
        User u = User.create("Ada", "L", "gitlog-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        users.saveAndFlush(u);

        String code = "code-secret-" + UUID.randomUUID();

        var start = CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/users/me/git-links/GITHUB/start"))
                .header("X-Principal-Type", "user")
                .header("X-User-Id", u.getId().toString())
                .header("X-User-Roles", "STUDENT")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(start.statusCode()).isEqualTo(200);
        String url = JSON.readTree(start.body()).get("authorizationUrl").asText();
        String state = url.substring(url.indexOf("state=") + "state=".length());

        var callback = CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/users/me/git-links/GITHUB/callback"))
                .header("Content-Type", "application/json")
                .header("X-Principal-Type", "user")
                .header("X-User-Id", u.getId().toString())
                .header("X-User-Roles", "STUDENT")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"code\":\"" + code + "\",\"state\":\"" + state + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(callback.statusCode()).isEqualTo(200);

        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains(code)
                        || e.getFormattedMessage().contains(state))
                .isEmpty();
    }
}
