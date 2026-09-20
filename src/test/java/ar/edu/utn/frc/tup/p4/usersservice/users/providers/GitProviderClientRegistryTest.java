package ar.edu.utn.frc.tup.p4.usersservice.users.providers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ErrorTypes;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitProviderClientRegistryTest {

    private static final GitProviderClient GITHUB = new GitProviderClient() {
        @Override public GitProvider provider() { return GitProvider.GITHUB; }
        @Override public URI authorizationUrl(String state) { return URI.create("https://example.test/" + state); }
        @Override public GitProviderIdentity exchange(String code) {
            return new GitProviderIdentity("1", "octocat");
        }
    };

    @Test
    void resolves_GITHUB_when_an_adapter_is_registered() {
        GitProviderClientRegistry registry = new GitProviderClientRegistry(List.of(GITHUB));
        assertThat(registry.require(GitProvider.GITHUB)).isSameAs(GITHUB);
    }

    @Test
    void fails_with_provider_not_supported_when_no_adapter() {
        GitProviderClientRegistry registry = new GitProviderClientRegistry(List.of());
        assertThatThrownBy(() -> registry.require(GitProvider.GITHUB))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getType())
                        .isEqualTo(ErrorTypes.PROVIDER_NOT_SUPPORTED));
    }
}
