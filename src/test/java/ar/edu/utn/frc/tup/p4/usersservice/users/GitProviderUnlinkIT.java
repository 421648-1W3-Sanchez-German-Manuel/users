package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.AdminDeactivationRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.GitProviderLinkRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.GitProviderLinkService;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Etapa 4 criteria 6–7: unlink clears the mirror; deactivate frees the provider account. */
class GitProviderUnlinkIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;
    @Autowired GitProviderLinkService gitLinks;
    @Autowired GitProviderLinkRepository linkRepo;
    @Autowired PasswordEncoder encoder;

    private User active(String email) {
        User u = User.create("Ana", "P", email, encoder.encode("validpassword1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    @Test
    void unlink_clears_github_username_and_does_not_reopen_first_login() {
        User u = active("unlink-" + UUID.randomUUID() + "@utn.edu.ar");
        u.completeOnboarding(true);
        u.clearFirstLogin();
        repo.saveAndFlush(u);
        gitLinks.link(u.getId(), GitProvider.GITHUB,
                new GitProviderIdentity("ext-" + UUID.randomUUID(), "octocat"));

        gitLinks.unlink(u.getId(), GitProvider.GITHUB);

        var me = users.me(u.getId());
        assertThat(me.githubUsername()).isNull();
        assertThat(me.firstLogin()).isFalse();
        assertThat(linkRepo.findByUserIdAndDeletedAtIsNull(u.getId())).isEmpty();
    }

    @Test
    void deactivating_a_user_frees_their_github_for_another_user() {
        String externalId = "shared-ext-" + UUID.randomUUID();
        User first = active("first-" + UUID.randomUUID() + "@utn.edu.ar");
        gitLinks.link(first.getId(), GitProvider.GITHUB, new GitProviderIdentity(externalId, "shared"));

        User actor = User.createAdmin("Ad", "Min", "admin-unlink-" + UUID.randomUUID() + "@utn.edu.ar",
                encoder.encode("validpassword1"), "v1");
        actor.changePassword(encoder.encode("validpassword1"));
        repo.saveAndFlush(actor);

        users.deactivate(actor.getId(), first.getId(), new AdminDeactivationRequest("na", "na", "na"));

        assertThat(linkRepo.findByUserIdAndDeletedAtIsNull(first.getId())).isEmpty();
        assertThat(repo.findById(first.getId())).get()
                .extracting(User::getGithubUsername).isNull();

        User second = active("second-" + UUID.randomUUID() + "@utn.edu.ar");
        gitLinks.link(second.getId(), GitProvider.GITHUB, new GitProviderIdentity(externalId, "shared"));
        assertThat(users.me(second.getId()).githubUsername()).isEqualTo("shared");
    }
}
