package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Etapa 4 criterion 5: GitHub disabled → tour alone is enough (DEC-GL-05 escape). */
class OnboardingGitHubDisabledIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;

    @Test
    void tour_alone_clears_first_login_when_github_disabled() {
        User u = User.create("Ana", "P", "onb-off-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        repo.saveAndFlush(u);

        users.completeOnboarding(u.getId(), true);

        assertThat(users.me(u.getId()).firstLogin()).isFalse();
        assertThat(users.me(u.getId()).githubUsername()).isNull();
    }
}
