package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-42 · criterio de DoD #30. */
class RateLimitLoginIT extends AbstractIntegrationTest {

    /**
     * MySQL y Redis son singletons compartidos SIN cleanup entre clases
     * (AbstractIntegrationTest). Con direcciones fijas, cualquier otro lote
     * que tome una de estas, o una corrida repetida en la misma JVM, produce
     * un 409 de clave duplicada en el INSERT del fixture y se lee como falla
     * del codigo bajo prueba.
     */
    private static final String SUF = "-" + UUID.randomUUID() + "@utn.edu.ar";

    @Autowired AuthService auth;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void al_sexto_FALLO_sobre_el_mismo_email_responde_429() {
        crear("rl1" + SUF);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("rl1" + SUF, "malamala1234"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
        }
        assertThatThrownBy(() -> auth.login("rl1" + SUF, "malamala1234"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus().value()).isEqualTo(429);
                    assertThat(((ApiException) e).getExtras()).containsKey("retryAfterSeconds");
                    assertThat(((ApiException) e).getType().toString()).endsWith("/too-many-attempts");
                });
    }

    @Test
    void un_login_EXITOSO_no_consume_presupuesto_y_limpia_los_fallos() {
        // It counts failures, not attempts: a legitimate user never hits the limit.
        crear("rl2" + SUF);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> auth.login("rl2" + SUF, "malamala1234"))
                    .isInstanceOf(ApiException.class);
        }
        auth.login("rl2" + SUF, "passwordvalida1");   // acierta -> limpia

        // Vuelve a tener las 5 oportunidades completas.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> auth.login("rl2" + SUF, "malamala1234"))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
        }
    }

    @Test
    void el_limite_es_por_email_no_global() {
        crear("rl3" + SUF);
        crear("rl4" + SUF);
        for (int i = 0; i < 6; i++) {
            try { auth.login("rl3" + SUF, "malamala1234"); } catch (ApiException ignored) { }
        }
        // La otra cuenta no quedo afectada.
        assertThatThrownBy(() -> auth.login("rl4" + SUF, "malamala1234"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(401);
    }

    @Test
    void al_sexto_DESAFIO_de_2fa_sobre_el_mismo_email_responde_429() {
        // Presupuesto separado del de fallos: acertar la password no lo limpia.
        // Sin esto, quien robo la password inunda de mails al dueno de la cuenta.
        crear("rl5" + SUF);
        for (int i = 0; i < 5; i++) {
            assertThat(auth.login("rl5" + SUF, "passwordvalida1").challengeId()).isNotBlank();
        }
        assertThatThrownBy(() -> auth.login("rl5" + SUF, "passwordvalida1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value()).isEqualTo(429);
    }

    private void crear(String email) {
        User u = User.create("A", "A", email, encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        repo.saveAndFlush(u);
    }
}