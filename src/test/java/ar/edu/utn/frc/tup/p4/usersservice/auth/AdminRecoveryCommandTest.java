package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.cli.AdminRecoveryCommand;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-32 - RF-ROL-04, DoD criterion #29. */
class AdminRecoveryCommandTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET = "el-secreto-de-instalacion";

    @DynamicPropertySource
    static void secretHash(DynamicPropertyRegistry registry) {
        String hash = new BCryptPasswordEncoder(4).encode(TEST_SECRET);
        registry.add("users.breakglass.secret-hash", () -> hash);
    }

    @Autowired AdminRecoveryCommand comando;
    @Autowired UserRepository repo;

    @Test
    void con_el_secreto_correcto_crea_un_ADMIN_con_cambio_forzado() {
        var id = comando.recuperar(TEST_SECRET,
                "Rescate", "Admin", "rescate-" + java.util.UUID.randomUUID() + "@utn.edu.ar",
                "passwordvalida1");

        assertThat(repo.findById(id)).get().satisfies(u -> {
            assertThat(u.getRole()).isEqualTo(Role.ADMIN);
            assertThat(u.mustChangePassword()).isTrue();
        });
    }

    @Test
    void con_el_secreto_incorrecto_no_crea_nada() {
        long antes = repo.count();
        assertThatThrownBy(() -> comando.recuperar("mal", "R", "A",
                "no-" + java.util.UUID.randomUUID() + "@utn.edu.ar", "passwordvalida1"))
                .isInstanceOf(SecurityException.class);
        assertThat(repo.count()).isEqualTo(antes);
    }

    @Test
    void el_secreto_nunca_aparece_en_el_mensaje_de_error() {
        assertThatThrownBy(() -> comando.recuperar("secreto-filtrable", "R", "A",
                "x-" + java.util.UUID.randomUUID() + "@utn.edu.ar", "passwordvalida1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageNotContaining("secreto-filtrable");
    }

    @Test
    @Disabled("espera L3 · T6 AccountEventPublisher / T7 NotificationEventPublisher")
    void el_breakglass_deja_RECUPERACION_ADMIN_en_el_outbox() {
        // TODO: cuando L3 provea AccountEventPublisher, verificar que
        // AdminRecoveryCommand escribe un OutboxEvent con topic "auditoria"
        // y payload conteniendo "RECUPERACION_ADMIN" DENTRO de tx.execute.
        // OutboxRepository es de L1 y ya existe.
    }

    @Test
    @Disabled("espera L3 · T6 AccountEventPublisher / T7 NotificationEventPublisher")
    void el_breakglass_manda_mail_a_todos_los_ADMIN_activos() {
        // TODO: cuando L3 provea NotificationEventPublisher, verificar que
        // AdminRecoveryCommand llama a mails.enviar(EmailType.BREAKGLASS_ALERT, ...)
        // para cada ADMIN activo distinto del recién creado.
        // EmailType y NotificationEventPublisher son de L3.
    }
}
