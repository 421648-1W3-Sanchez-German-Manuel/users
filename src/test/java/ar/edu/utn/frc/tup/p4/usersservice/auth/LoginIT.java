package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.OutboxRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Criterios de DoD #3 (login completo, de punta a punta) y #4 (segundo
 * login sobrescribe la sesion).
 */
@Import(TestOtpSpy.Config.class)   // el spy del Step 5, solo para este test
class LoginIT extends AbstractIntegrationTest {

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
    @Autowired TokenStore store;
    @Autowired OutboxRepository outbox;      // DoD #3: el mail tiene que salir
    @Autowired TestOtpSpy otpSpy;   // captura el code generado; ver Step 5

    private User crearActivo(String email, String password) {
        User u = User.create("Ana", "Perez", email, encoder.encode(password), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    @Test
    void la_fase_1_NO_devuelve_tokens() throws Exception {
        crearActivo("f1" + SUF, "passwordvalida1");
        var r = auth.login("f1" + SUF, "passwordvalida1");
        assertThat(r.challengeId()).isNotBlank();
        // Si la fase 1 devolviera tokens, el 2FA seria decorativo.
        assertThat(r.toString()).doesNotContain("eyJ");
    }

    @Test
    void la_fase_1_RENDERIZA_el_mail_y_publica_el_evento() {
        // Criterio de DoD #3, tramo "code 2FA generado y mail renderizado
        // -> event published". Without this assert, the login could issue
        // perfect tokens and never send the code: green in the tests,
        // roto para el usuario.
        crearActivo("f2a" + SUF, "passwordvalida1");
        long antes = outbox.count();

        auth.login("f2a" + SUF, "passwordvalida1");

        assertThat(outbox.count()).isGreaterThan(antes);
        assertThat(outbox.findAll()).anySatisfy(e -> {
            assertThat(e.getPayload()).contains("EMAIL_2FA");
            assertThat(e.getPayload()).contains("f2a" + SUF);
            // The mail goes out ALREADY BUILT: subject + html, not a templateId.
            assertThat(e.getPayload()).contains("\"asunto\"").contains("\"html\"");
            // And the code NEVER appears in the audit event or in a log.
            assertThat(e.getTopic()).isNotBlank();
        });
    }

    @Test
    void la_fase_2_con_el_codigo_correcto_emite_los_dos_tokens() throws Exception {
        User u = crearActivo("f2" + SUF, "passwordvalida1");
        var desafio = auth.login("f2" + SUF, "passwordvalida1");

        var tokens = auth.verificarDosFa(desafio.challengeId(), otpSpy.ultimoCodigo());

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        var claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("users-service");
        assertThat(claims.getStringClaim("est")).isEqualTo("ACTIVE");
        // DEC-22: the post-2FA login is the ONLY operation that writes the session.
        assertThat(store.sidDe(u.getId())).contains(claims.getStringClaim("sid"));
    }

    @Test
    void un_segundo_login_pisa_la_sesion_del_primero() throws Exception {
        User u = crearActivo("f3" + SUF, "passwordvalida1");

        var d1 = auth.login("f3" + SUF, "passwordvalida1");
        var t1 = auth.verificarDosFa(d1.challengeId(), otpSpy.ultimoCodigo());
        String sid1 = SignedJWT.parse(t1.accessToken()).getJWTClaimsSet().getStringClaim("sid");

        var d2 = auth.login("f3" + SUF, "passwordvalida1");
        var t2 = auth.verificarDosFa(d2.challengeId(), otpSpy.ultimoCodigo());
        String sid2 = SignedJWT.parse(t2.accessToken()).getJWTClaimsSet().getStringClaim("sid");

        assertThat(sid1).isNotEqualTo(sid2);
        assertThat(store.sidDe(u.getId())).contains(sid2);   // gana el ultimo
    }

    @Test
    void una_cuenta_PENDIENTE_CURSO_puede_loguearse_y_su_token_lo_refleja() throws Exception {
        // INC-19: RF-USR-05f prohibe el ACCESO A FUNCIONALIDAD, no la emision
        // of the token. The token is how the person queries
        // GET /me and finds out what is missing. The set of features
        // alcanzables es vacio (DEC-23, gate grueso en el Gateway).
        User u = User.create("B", "B", "pend" + SUF,
                encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.activate();                     // -> PENDING_COURSE
        repo.saveAndFlush(u);

        var d = auth.login("pend" + SUF, "passwordvalida1");
        var t = auth.verificarDosFa(d.challengeId(), otpSpy.ultimoCodigo());

        assertThat(SignedJWT.parse(t.accessToken()).getJWTClaimsSet().getStringClaim("est"))
                .isEqualTo("PENDING_COURSE");
    }

    @Test
    void password_incorrecta_y_email_inexistente_dan_el_MISMO_error() {
        crearActivo("f4" + SUF, "passwordvalida1");
        String m1 = capturar(() -> auth.login("f4" + SUF, "otracosa1234"));
        String m2 = capturar(() -> auth.login("nadie" + SUF, "otracosa1234"));
        assertThat(m1).isEqualTo(m2);
    }

    @Test
    void un_codigo_2fa_incorrecto_no_emite_tokens() {
        crearActivo("f5" + SUF, "passwordvalida1");
        var d = auth.login("f5" + SUF, "passwordvalida1");
        assertThatThrownBy(() -> auth.verificarDosFa(d.challengeId(), "000000"))
                .isInstanceOf(ApiException.class);
    }

    private String capturar(Runnable r) {
        try { r.run(); return "no-fallo"; }
        catch (ApiException e) { return e.getMessage(); }
    }
}