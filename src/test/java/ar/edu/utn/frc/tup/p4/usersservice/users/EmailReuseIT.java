package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-21 - DoD criterion #22. */
class EmailReuseIT extends AbstractIntegrationTest {

    @Autowired UserRepository repo;

    @Test
    void a_deactivated_email_can_be_registered_again() {
        User first = repo.saveAndFlush(
                User.create("Ana", "Perez", "reuse@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
        first.deactivate();
        repo.saveAndFlush(first);

        User second = repo.saveAndFlush(
                User.create("Ana", "Perez", "reuse@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(repo.findByEmailAndDeletedAtIsNull("reuse@utn.edu.ar"))
                .get().extracting(User::getId).isEqualTo(second.getId());
    }

    @Test
    void two_active_accounts_with_the_same_email_break_the_unique_key() {
        repo.saveAndFlush(User.create("A", "A", "dup@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
        assertThatThrownBy(() ->
                repo.saveAndFlush(User.create("B", "B", "dup@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void the_email_is_normalised_to_lowercase() {
        // DEC-20 rule 4: the collation is the safety net, normalising in the
    // application is the mechanism.
        User u = repo.saveAndFlush(
                User.create("A", "A", "UPPERCASE@UTN.EDU.AR", "$2a$12$h", Role.STUDENT, "v1"));
        assertThat(u.getEmail()).isEqualTo("uppercase@utn.edu.ar");
    }
}
