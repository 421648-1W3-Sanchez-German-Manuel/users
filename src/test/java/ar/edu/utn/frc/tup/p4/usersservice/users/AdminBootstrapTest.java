package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.cli.AdminBootstrap;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** RF-USR-01 - the initial ADMIN of a clean installation. */
class AdminBootstrapTest {

    UserRepository repo = mock(UserRepository.class);
    PasswordEncoder encoder = new BCryptPasswordEncoder(4);   // low cost: it's a test
    TransactionTemplate tx = mock(TransactionTemplate.class);

    private AdminBootstrap bootstrap(String password) {
        when(tx.execute(any())).thenAnswer(inv -> {
            var callback = inv.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        return new AdminBootstrap(repo, encoder, tx,
                "admin@frc.utn.edu.ar", password, "Admin", "Initial", "v1");
    }

    @Test
    void creates_an_ADMIN_required_to_change_the_password() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        bootstrap("validpassword1").run(null);

        verify(repo).saveAndFlush(any());
    }

    @Test
    void does_nothing_when_an_active_ADMIN_already_exists() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(1L);
        bootstrap("validpassword1").run(null);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void a_weak_environment_password_makes_startup_fail() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bootstrap("short").run(null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("12 characters");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void a_missing_configured_password_generates_one_that_meets_the_policy() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        bootstrap("").run(null);

        verify(repo).saveAndFlush(any());
    }

    @Test
    @Disabled("waiting for L3 T6 AccountEventPublisher / T7 NotificationEventPublisher")
    void initial_ADMIN_creation_leaves_ADMIN_INICIAL_CREADO_in_the_outbox() {
        // TODO: when L3 provides AccountEventPublisher, verify that
        // AdminBootstrap writes an OutboxEvent with topic "auditoria"
        // and a payload containing "ADMIN_INICIAL_CREADO" INSIDE tx.execute.
        // OutboxRepository belongs to L1 and already exists.
    }
}
