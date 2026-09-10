package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.TokenResponse;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-22 - the intuition that "if the refresh does not rotate the sid, an old
 * old one revives the session" is INVERTED: rotating is what allows reviving it.
 * Of the four combinations (new/same sid x writes/does not write Redis) only
 * one works: same sid + does not write.
 */
class SingleSessionRefreshIT extends AbstractIntegrationTest {

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

    private UUID crear(String email) {
        User u = User.create("A", "A", email, encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void el_refresh_NO_genera_sid_nuevo_ni_escribe_redis() throws Exception {
        UUID id = crear("ref1" + SUF);
        TokenResponse t1 = auth.emitirParDeTokens(id);
        String sid = store.sidDe(id).orElseThrow();

        TokenResponse t2 = auth.refrescar(t1.refreshToken());

        assertThat(SignedJWT.parse(t2.accessToken()).getJWTClaimsSet().getStringClaim("sid"))
                .isEqualTo(sid);
        assertThat(store.sidDe(id)).contains(sid);   // la key no cambio
    }

    @Test
    void el_dispositivo_SUPERADO_recibe_401_y_su_familia_queda_revocada() {
        // A logueado, B se loguea, A intenta refrescar.
        UUID id = crear("ref2" + SUF);
        TokenResponse deA = auth.emitirParDeTokens(id);
        TokenResponse deB = auth.emitirParDeTokens(id);   // pisa la sesion

        assertThatThrownBy(() -> auth.refrescar(deA.refreshToken()))
                .isInstanceOf(ApiException.class);

        // El refresh de B sigue funcionando.
        assertThat(auth.refrescar(deB.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void reusar_un_refresh_ya_rotado_revoca_TODA_la_familia() {
        UUID id = crear("ref3" + SUF);
        TokenResponse t1 = auth.emitirParDeTokens(id);
        TokenResponse t2 = auth.refrescar(t1.refreshToken());   // t1 queda rotado

        // A theft signal: somebody else holds the old refresh token.
        assertThatThrownBy(() -> auth.refrescar(t1.refreshToken())).isInstanceOf(ApiException.class);
        // And the new one dies too: the whole family was revoked.
        assertThatThrownBy(() -> auth.refrescar(t2.refreshToken())).isInstanceOf(ApiException.class);
    }

    @Test
    void el_refresh_RELEE_el_estado_de_la_base() throws Exception {
        // DEC-23: this is what makes refreshing the propagation mechanism
        // rapida cuando la cuenta gana acceso.
        User u = User.create("A", "A", "ref4" + SUF,
                encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.activate();                       // PENDING_COURSE
        repo.saveAndFlush(u);

        TokenResponse t1 = auth.emitirParDeTokens(u.getId());
        assertThat(SignedJWT.parse(t1.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("PENDING_COURSE");

        u.activateAfterCourseValidation();
        repo.saveAndFlush(u);

        TokenResponse t2 = auth.refrescar(t1.refreshToken());
        assertThat(SignedJWT.parse(t2.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("ACTIVE");      // sin re-login
    }
}