package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-02 · el logout borra session:{userId}: corta el access al instante. */
class LogoutIT extends AbstractIntegrationTest {

    /**
     * MySQL y Redis son singletons compartidos SIN cleanup entre clases
     * (AbstractIntegrationTest). Con direcciones fijas, cualquier otro lote
     * que tome una de estas, o una corrida repetida en la misma JVM, produce
     * un 409 de clave duplicada en el INSERT del fixture y se lee como falla
     * del codigo bajo prueba.
     */
    private static final String SUF = "-" + UUID.randomUUID() + "@utn.edu.ar";

    @Autowired AuthService auth;
    @Autowired TokenStore store;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void el_logout_borra_la_key_de_sesion() {
        User u = User.create("A", "A", "out" + SUF,
                encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        UUID id = repo.saveAndFlush(u).getId();

        var t = auth.emitirParDeTokens(id);
        assertThat(store.sidDe(id)).isPresent();

        auth.logout(id, t.refreshToken());

        // Sin la key, el Gateway responde 401 "sesion cerrada" (DEC-01),
        // without waiting the ~10 min of exp.
        assertThat(store.sidDe(id)).isEmpty();
        assertThatThrownBy(() -> auth.refrescar(t.refreshToken())).isInstanceOf(ApiException.class);
    }
}