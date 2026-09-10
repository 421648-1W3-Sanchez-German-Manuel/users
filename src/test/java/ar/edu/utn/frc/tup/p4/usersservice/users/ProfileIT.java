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
    void el_perfil_NO_expone_email_legajo_ni_estado() {
        User u = User.create("Ana", "Perez", "perf-" + java.util.UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.setLegajo("76543");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding("anaperez", "avatars/a.png", true);
        repo.saveAndFlush(u);

        var perfil = users.perfil(u.getId());

        assertThat(perfil.firstNames()).isEqualTo("Ana");
        assertThat(perfil.githubUsername()).isEqualTo("anaperez");
        assertThat(perfil.toString())
                .doesNotContain("perf@utn.edu.ar")
                .doesNotContain("76543")
                .doesNotContain("ACTIVE");
    }

    @Test
    void solo_el_handle_de_github_nunca_una_URL() {
        User u = User.create("B", "Q", "gh-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        u.completeOnboarding("bq", null, true);
        repo.saveAndFlush(u);
        assertThat(users.perfil(u.getId()).githubUsername()).doesNotContain("http");
    }
}
