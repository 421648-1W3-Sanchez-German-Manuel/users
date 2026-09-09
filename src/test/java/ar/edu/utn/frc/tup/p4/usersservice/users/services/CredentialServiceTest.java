package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.impl.CredentialServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DoD criterion #8: it is invoked DIRECTLY, from a unit test, WITHOUT
 * starting any HTTP server. It is the proof that the boundary between modules
 * is a Java call and not a network call.
 */
class CredentialServiceTest {

    PasswordEncoder encoder = new BCryptPasswordEncoder(4);   // low cost: this is a test
    UserRepository repo = mock(UserRepository.class);
    CredentialService service = new CredentialServiceImpl(repo, encoder);

    private User active(String password) {
        User u = User.create("Ana", "Perez", "ana@utn.edu.ar", encoder.encode(password), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return u;
    }

    @Test
    void valid_credentials_return_the_user_data() {
        when(repo.findByEmailAndDeletedAtIsNull("ana@utn.edu.ar"))
                .thenReturn(Optional.of(active("passwordvalida1")));

        var r = service.verifyCredentials("ana@utn.edu.ar", "passwordvalida1");

        assertThat(r).isNotNull();
        assertThat(r.roles()).containsExactly(Role.STUDENT);
        assertThat(r.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(r.firstNames()).isEqualTo("Ana");
    }

    @Test
    void a_wrong_password_returns_null() {
        when(repo.findByEmailAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(active("passwordvalida1")));
        assertThat(service.verifyCredentials("ana@utn.edu.ar", "otracosa1234")).isNull();
    }

    @Test
    void an_unknown_email_returns_null_just_like_a_wrong_password() {
        // Anti-enumeration: the caller cannot tell the two cases apart.
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());
        assertThat(service.verifyCredentials("nadie@utn.edu.ar", "loquesea1234")).isNull();
    }

    @Test
    void the_email_is_looked_up_lowercased() {
        when(repo.findByEmailAndDeletedAtIsNull("ana@utn.edu.ar"))
                .thenReturn(Optional.of(active("passwordvalida1")));
        assertThat(service.verifyCredentials("ANA@UTN.EDU.AR", "passwordvalida1")).isNotNull();
    }

    @Test
    void the_hash_never_leaves_the_call() {
        when(repo.findByEmailAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(active("passwordvalida1")));
        var r = service.verifyCredentials("ana@utn.edu.ar", "passwordvalida1");
        assertThat(r.toString()).doesNotContain("$2a$");
    }
}