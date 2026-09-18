package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.GitProviderLink;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.GitProviderLinkRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 4 criteria 8–9 (DEC-GL-20): V43 clears typed handles without a link;
 * does not clear a legitimate mirror; does not reopen first_login.
 */
class LegacyGithubHandleIT extends AbstractIntegrationTest {

    private static final String V43_SQL = """
            UPDATE users
               SET github_username = NULL, updated_at = NOW(6)
             WHERE github_username IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM user_git_provider_links l
                                WHERE l.user_id = users.id AND l.deleted_at IS NULL)
            """;

    @Autowired UserRepository users;
    @Autowired GitProviderLinkRepository links;
    @Autowired JdbcTemplate jdbc;

    @Test
    void typed_handle_without_link_is_cleared_without_reopening_first_login() {
        User u = User.create("Ana", "P", "legacy-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding(true);
        u.clearFirstLogin();
        u.mirrorGithubUsername("typed-handle");
        users.saveAndFlush(u);

        jdbc.update(V43_SQL);

        User reloaded = users.findById(u.getId()).orElseThrow();
        assertThat(reloaded.getGithubUsername()).isNull();
        assertThat(reloaded.isFirstLogin()).isFalse();
    }

    @Test
    void V43_does_not_clear_mirror_of_user_with_active_link() {
        User u = User.create("Bob", "Q", "linked-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.mirrorGithubUsername("real-octocat");
        users.saveAndFlush(u);
        links.saveAndFlush(GitProviderLink.create(
                u.getId(), GitProvider.GITHUB, "ext-" + UUID.randomUUID(), "real-octocat"));

        jdbc.update(V43_SQL);

        assertThat(users.findById(u.getId())).get()
                .extracting(User::getGithubUsername).isEqualTo("real-octocat");
    }
}
