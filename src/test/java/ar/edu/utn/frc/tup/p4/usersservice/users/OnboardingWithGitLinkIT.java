package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.GitProviderLinkService;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 4 criteria 2 and 4 (service level): with GitHub enabled, tour alone does
 * not clear first_login; link + tour does.
 *
 * Credentials are set as well as the flag: the requirement only applies while
 * an adapter is registered and a person can actually satisfy it. See
 * OnboardingGitHubMisconfiguredIT for the flag without credentials.
 */
class OnboardingWithGitLinkIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void enableGithub(DynamicPropertyRegistry registry) {
        registry.add("users.git-providers.github.enabled", () -> "true");
        registry.add("users.git-providers.github.client-id", () -> "cid-test");
        registry.add("users.git-providers.github.client-secret", () -> "secret-test");
        registry.add("users.git-providers.github.redirect-uri", () -> "http://front/vinculacion/callback");
    }

    @Autowired UserService users;
    @Autowired UserRepository repo;
    @Autowired GitProviderLinkService gitLinks;

    private User activeUser() {
        User u = User.create("Ana", "P", "onb-link-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    @Test
    void tour_alone_does_not_clear_first_login_when_github_enabled() {
        User u = activeUser();
        users.completeOnboarding(u.getId(), true);
        assertThat(users.me(u.getId()).guidedTourCompleted()).isTrue();
        assertThat(users.me(u.getId()).firstLogin()).isTrue();
    }

    @Test
    void link_plus_tour_clears_first_login_and_mirrors_username() {
        User u = activeUser();
        users.completeOnboarding(u.getId(), true);
        String ext = "ext-" + UUID.randomUUID();
        gitLinks.link(u.getId(), GitProvider.GITHUB, new GitProviderIdentity(ext, "octocat"));

        var me = users.me(u.getId());
        assertThat(me.githubUsername()).isEqualTo("octocat");
        assertThat(me.firstLogin()).isFalse();
    }
}
