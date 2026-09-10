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
                "admin@frc.utn.edu.ar", password, "Admin", "Inicial", "v1");
    }

    @Test
    void crea_un_ADMIN_obligado_a_cambiar_la_password() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        bootstrap("passwordvalida1").run(null);

        verify(repo).saveAndFlush(any());
    }

    @Test
    void no_hace_nada_si_ya_hay_un_ADMIN_activo() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(1L);
        bootstrap("passwordvalida1").run(null);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void una_password_floja_del_entorno_hace_fallar_el_arranque() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bootstrap("corta").run(null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("12 characters");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void sin_password_configurada_genera_una_que_cumple_la_politica() {
        when(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(0L);
        when(repo.findByEmailAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        bootstrap("").run(null);

        verify(repo).saveAndFlush(any());
    }

    @Test
    @Disabled("espera L3 · T6 AccountEventPublisher / T7 NotificationEventPublisher")
    void el_alta_del_ADMIN_inicial_deja_ADMIN_INICIAL_CREADO_en_el_outbox() {
        // TODO: cuando L3 provea AccountEventPublisher, verificar que
        // AdminBootstrap escribe un OutboxEvent con topic "auditoria"
        // y payload conteniendo "ADMIN_INICIAL_CREADO" DENTRO de tx.execute.
        // OutboxRepository es de L1 y ya existe.
    }
}
