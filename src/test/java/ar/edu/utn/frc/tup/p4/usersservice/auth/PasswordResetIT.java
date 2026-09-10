package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-16 - two endpoints, not one. `flujos` §06 drew them as the same POST. */
@Import(TestResetSpy.Config.class)   // el spy del Step 4, solo para este test
class PasswordResetIT extends AbstractIntegrationTest {

    @Autowired PasswordService passwords;
    @Autowired CredentialService credenciales;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired TokenStore store;
    @Autowired TestResetSpy spy;      // captura el token, igual que TestOtpSpy
                                      // (solo RESET_PASSWORD: ver Step 4)

    private User crear(String email) {
        User u = User.create("Ana", "P", email, encoder.encode("passwordvalida1"), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    @Test
    void pedir_reset_responde_IGUAL_exista_o_no_el_email() {
        crear("res1@utn.edu.ar");
        // Anti-enumeration: an attacker cannot discover which e-mails exist.
        assertThat(passwords.pedirReset("res1@utn.edu.ar"))
                .isEqualTo(passwords.pedirReset("nadie@utn.edu.ar"));
    }

    @Test
    void confirmar_cambia_la_password_y_revoca_las_sesiones() {
        User u = crear("res2@utn.edu.ar");
        store.guardarSesion(u.getId(), "sid-viejo");

        passwords.pedirReset("res2@utn.edu.ar");
        passwords.confirmarReset(spy.ultimoToken(), "nuevapasswordok1");

        assertThat(credenciales.verifyCredentials("res2@utn.edu.ar", "nuevapasswordok1")).isNotNull();
        assertThat(credenciales.verifyCredentials("res2@utn.edu.ar", "passwordvalida1")).isNull();
        // A changed password has to close the old sessions.
        assertThat(store.sidDe(u.getId())).isEmpty();
    }

    @Test
    void el_token_de_reset_es_de_UN_SOLO_uso() {
        crear("res3@utn.edu.ar");
        passwords.pedirReset("res3@utn.edu.ar");
        String token = spy.ultimoToken();

        passwords.confirmarReset(token, "nuevapasswordok1");
        assertThatThrownBy(() -> passwords.confirmarReset(token, "otrapasswordok2"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void el_reset_respeta_la_politica_de_password() {
        crear("res4@utn.edu.ar");
        passwords.pedirReset("res4@utn.edu.ar");
        assertThatThrownBy(() -> passwords.confirmarReset(spy.ultimoToken(), "corta"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void el_cambio_voluntario_exige_la_password_actual() {
        User u = crear("res5@utn.edu.ar");
        assertThatThrownBy(() -> passwords.cambiar(u.getId(), "equivocada12", "nuevapasswordok1"))
                .isInstanceOf(ApiException.class);
        passwords.cambiar(u.getId(), "passwordvalida1", "nuevapasswordok1");
        assertThat(credenciales.verifyCredentials("res5@utn.edu.ar", "nuevapasswordok1")).isNotNull();
    }
}