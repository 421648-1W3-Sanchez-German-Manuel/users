package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Counterpart of GitProviderDisabledIT: with a {@code client-id} the real
 * adapter registers in the Spring context (condition + RestClient wiring).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class GitProviderEnabledIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void withCredentials(DynamicPropertyRegistry registry) {
        registry.add("users.git-providers.github.client-id", () -> "cid-test");
        registry.add("users.git-providers.github.client-secret", () -> "secret-test");
        registry.add("users.git-providers.github.redirect-uri", () -> "http://front/vinculacion/callback");
    }

    @Autowired List<GitProviderClient> clients;

    @Test
    void with_client_id_the_github_adapter_registers() {
        assertThat(clients).hasSize(1);
        assertThat(clients.get(0).provider().name()).isEqualTo("GITHUB");
        assertThat(clients.get(0).authorizationUrl("st").toString())
                .startsWith("https://github.com/login/oauth/authorize")
                .contains("client_id=cid-test");
    }
}
