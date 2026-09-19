package ar.edu.utn.frc.tup.p4.usersservice.users.providers.github;

import ar.edu.utn.frc.tup.p4.usersservice.config.GitProviderProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * GitHub as a {@link GitProviderClient} (SPEC-git-provider-linking §4).
 *
 * Only this class knows the provider wire protocol. The domain sees identities,
 * never tokens (DEC-GL-02): the access token is used once for {@code GET /user}
 * and discarded (DEC-GL-04). The profile {@code avatar_url} is never read and
 * never stored (DEC-GL-21). Nothing is logged here — in particular never the
 * {@code client_secret}.
 *
 * DEC-GL-05: without a {@code client-id} this adapter does not register, and
 * every endpoint answers {@code provider-not-supported} while the app boots
 * normally.
 */
@Component
@ConditionalOnExpression("'${users.git-providers.github.client-id:}'.length() > 0")
public class GithubProviderClient implements GitProviderClient {

    private static final String AUTHORIZE_PATH = "/login/oauth/authorize";
    private static final String TOKEN_PATH = "/login/oauth/access_token";
    private static final String USER_PATH = "/user";

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
        this.props = props;
        // Static factory, not an injected builder: no container bean required.
        this.rest = RestClient.builder().build();
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
            if (token == null || token.access_token() == null || token.access_token().isBlank()) {
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
        } catch (RuntimeException e) {
            // Timeouts, refused connections, unreadable bodies: all of them are
            // "the provider did not answer", never a bare 500 (§4.5 #4).
            throw ApiException.providerUnavailable();
        }
    }

    private record TokenResponse(String access_token, String token_type, String scope) { }

    /** Only id + login are read. avatar_url is ignored on purpose (DEC-GL-21). */
    private record UserResponse(long id, String login) { }
}
