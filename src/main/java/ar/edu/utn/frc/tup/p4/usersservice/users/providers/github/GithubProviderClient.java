package ar.edu.utn.frc.tup.p4.usersservice.users.providers.github;

import ar.edu.utn.frc.tup.p4.usersservice.config.GitProviderProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * GitHub as a {@link GitProviderClient} (SPEC-git-provider-linking §4).
 *
 * Only this class knows the provider wire protocol. The domain sees identities,
 * never tokens (DEC-GL-02): the access token is used once for {@code GET /user}
 * and discarded (DEC-GL-04). The profile {@code avatar_url} is never read and
 * never stored (DEC-GL-21). Nothing is logged here — in particular never the
 * {@code client_secret}, the authorization code or the access token.
 *
 * DEC-GL-05: this adapter registers only when linking is enabled AND a
 * {@code client-id} is set. Otherwise every endpoint answers
 * {@code provider-not-supported} while the app boots normally.
 */
@Component
@ConditionalOnExpression(GitProviderProperties.ACTIVE_CONDITION)
public class GithubProviderClient implements GitProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GithubProviderClient.class);

    private static final String AUTHORIZE_PATH = "/login/oauth/authorize";
    private static final String TOKEN_PATH = "/login/oauth/access_token";
    private static final String USER_PATH = "/user";

    /**
     * Both are required. The callback runs on a Tomcat worker, so a provider
     * that accepts the connection and then goes quiet would pin that thread
     * for good and the provider-unavailable path below would never run.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final GitProviderProperties props;
    private final RestClient rest;
    private final String webBase;
    private final String apiBase;
    @Autowired
    public GithubProviderClient(GitProviderProperties props) {
        this(props, "https://github.com", "https://api.github.com");
    }

    /** Test-only: same client against a mocked HTTP server. */
    GithubProviderClient(GitProviderProperties props, String webBase, String apiBase) {
        this(props, webBase, apiBase, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /** Test-only: shorter timeouts, so the timeout path can be proven in milliseconds. */
    GithubProviderClient(GitProviderProperties props, String webBase, String apiBase,
                         Duration connectTimeout, Duration readTimeout) {
        this.props = props;
        // Static factory, not an injected builder: no container bean required.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.webBase = webBase;
        this.apiBase = apiBase;
    }

    @Override
    public GitProvider provider() { return GitProvider.GITHUB; }

    @Override
    public URI authorizationUrl(String state) {
        // DEC-GL-10: scope comes from config and is empty by default.
        return UriComponentsBuilder.fromUriString(webBase + AUTHORIZE_PATH)
                .queryParam("client_id", props.clientId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("scope", props.scope())
                .queryParam("state", state)
                .queryParam("prompt", "select_account")
                .encode()
                .build()
                .toUri();
    }

    @Override
    public GitProviderIdentity exchange(String authorizationCode) {
        try {
            TokenResponse token = rest.post().uri(webBase + TOKEN_PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "client_id", props.clientId(),
                            "client_secret", props.clientSecret(),
                            "code", authorizationCode,
                            "redirect_uri", props.redirectUri()))
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            (req, res) -> { throw ApiException.providerUnavailable(); })
                    .body(TokenResponse.class);
            if (token == null) {
                throw ApiException.providerUnavailable();
            }
            // GitHub answers 200 with an `error` body when the code expired or
            // was already spent, which is the commonest failure a person can
            // cause. The status hooks above never see it, so without this the
            // user reads "the provider did not respond". Reported as
            // invalid-link-state: same 400, same way out (start again), and the
            // front already has copy for it — a new type would need a front
            // release to match.
            if (token.error() != null && !token.error().isBlank()) {
                log.warn("GitHub rejected the authorization code: {}", token.error());
                throw ApiException.invalidLinkState();
            }
            if (token.access_token() == null || token.access_token().isBlank()) {
                throw ApiException.providerUnavailable();
            }
            UserResponse me = rest.get().uri(apiBase + USER_PATH)
                    .headers(h -> h.setBearerAuth(token.access_token()))
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            (req, res) -> { throw ApiException.providerUnavailable(); })
                    .body(UserResponse.class);
            if (me == null || me.login() == null || me.login().isBlank()) {
                throw ApiException.providerUnavailable();
            }
            // DEC-GL-01: the key is the numeric id, never the login.
            return new GitProviderIdentity(String.valueOf(me.id()), me.login());
        } catch (ApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Timeouts, refused connections, DNS: the message carries the URI
            // and nothing else, so it is safe to log, and it is the only clue
            // anyone gets about why a callback failed (§4.5 #4).
            log.warn("GitHub is unreachable: {}", e.getMessage());
            throw ApiException.providerUnavailable();
        } catch (RuntimeException e) {
            // Unreadable body, mapping errors, a null client secret. The
            // message of these can quote the response, which carries the
            // access token, so only the type is logged.
            log.warn("GitHub exchange failed: {}", e.getClass().getName());
            throw ApiException.providerUnavailable();
        }
    }

    private record TokenResponse(String access_token, String token_type, String scope, String error) { }

    /** Only id + login are read. avatar_url is ignored on purpose (DEC-GL-21). */
    private record UserResponse(long id, String login) { }
}
