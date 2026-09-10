package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RF-USR-04 - step 2: proving possession of the e-mail is a single-use LINK,
 * single use. This IT covers release criterion #26.
 */
@Import(TestActivationSpy.Config.class)
class ActivationLinkIT extends AbstractIntegrationTest {

    @Autowired RegistrationService registro;
    @Autowired UserRepository repo;
    @Autowired TestActivationSpy mailSpy;
    @Autowired StringRedisTemplate redis;

    private void altaAlumno(String email) {
        registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", "v1");
    }

    private AccountStatus estadoDe(String email) {
        return repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getAccountStatus();
    }

    @Test
    void el_enlace_correcto_activa_la_cuenta() {
        altaAlumno("act1@utn.edu.ar");
        assertThat(estadoDe("act1@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_EMAIL);

        registro.activate(mailSpy.ultimoTokenActivacion());

        // STUDENT -> PENDING_COURSE, no ACTIVE: falta que Cursos valide el legajo.
        assertThat(estadoDe("act1@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void el_enlace_se_usa_UNA_sola_vez() {
        // The second click can neither reactivate nor leak that the account exists.
        altaAlumno("act2@utn.edu.ar");
        String token = mailSpy.ultimoTokenActivacion();

        registro.activate(token);
        assertThatThrownBy(() -> registro.activate(token)).isInstanceOf(ApiException.class);
    }

    @Test
    void enlace_usado_e_inexistente_dan_LA_MISMA_respuesta() {
        // Anti-enumeracion: el detail no distingue vencido, usado ni inventado.
        altaAlumno("act3@utn.edu.ar");
        String token = mailSpy.ultimoTokenActivacion();
        registro.activate(token);

        String usado = capturar(() -> registro.activate(token));
        String inventado = capturar(() -> registro.activate("token-que-no-existe-de-largo-suficiente"));
        assertThat(usado).isEqualTo(inventado);
    }

    @Test
    void reenviar_genera_uno_nuevo_e_invalida_el_anterior() {
        // Without the per-e-mail index the old link would stay alive in parallel.
        altaAlumno("act4@utn.edu.ar");
        String viejo = mailSpy.ultimoTokenActivacion();

        registro.reenviarActivacion("act4@utn.edu.ar");
        String nuevo = mailSpy.ultimoTokenActivacion();

        assertThat(nuevo).isNotEqualTo(viejo);
        assertThatThrownBy(() -> registro.activate(viejo)).isInstanceOf(ApiException.class);

        registro.activate(nuevo);
        assertThat(estadoDe("act4@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    @Test
    void reenviar_a_un_email_inexistente_responde_igual_que_a_uno_real() {
        altaAlumno("act5@utn.edu.ar");
        assertThat(registro.reenviarActivacion("nadie@utn.edu.ar"))
                .isEqualTo(registro.reenviarActivacion("act5@utn.edu.ar"));
    }

    @Test
    void el_token_no_se_guarda_en_claro() {
        // Whoever can read Redis must not be able to activate other people's accounts.
        altaAlumno("act6@utn.edu.ar");
        String token = mailSpy.ultimoTokenActivacion();

        assertThat(redis.hasKey("activacion:" + token)).isFalse();
        registro.activate(token);   // pero el token del mail si funciona
        assertThat(estadoDe("act6@utn.edu.ar")).isEqualTo(AccountStatus.PENDING_COURSE);
    }

    private String capturar(Runnable r) {
        try { r.run(); return "no-fallo"; } catch (ApiException e) { return e.getMessage(); }
    }
}
