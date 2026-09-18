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

/** DEC-36 - the route accepts person tokens in addition to MS. */
class ProfileIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;

    @Test
    void profile_does_NOT_expose_email_legajo_or_status() {
        User u = User.create("Ana", "Perez", "profile-" + java.util.UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.setLegajo("76543");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding(true);
        u.clearFirstLogin();
        u.mirrorGithubUsername("anaperez");
        repo.saveAndFlush(u);

        var profile = users.profile(u.getId());

        assertThat(profile.firstNames()).isEqualTo("Ana");
        assertThat(profile.githubUsername()).isEqualTo("anaperez");
        assertThat(profile.toString())
                .doesNotContain("profile@utn.edu.ar")
                .doesNotContain("76543")
                .doesNotContain("ACTIVE");
    }

    @Test
    void github_contains_only_the_handle_and_never_a_URL() {
        User u = User.create("B", "Q", "gh-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding(true);
        u.clearFirstLogin();
        u.mirrorGithubUsername("bq");
        repo.saveAndFlush(u);
        assertThat(users.profile(u.getId()).githubUsername()).doesNotContain("http");
    }
}
