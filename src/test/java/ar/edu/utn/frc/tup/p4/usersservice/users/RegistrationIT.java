package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.OutboxRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.EmailWhitelist;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.EmailWhitelistRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DoD #2: alta de alumno con codigo de invitacion en el mismo form, la cuenta
 * pasa PENDING_EMAIL -> PENDING_COURSE al activar el mail, y se publica
 * ALUMNO_REGISTRADO recien ahi (DEC-34). DoD #17 (mitad de alta): sin
 * tycAceptado, 400 (DEC-11) — la otra mitad (baja de ADMIN) es de AdminRulesIT.
 */
@Import(TestActivationSpy.Config.class)
class RegistrationIT extends AbstractIntegrationTest {

    @Autowired RegistrationService registro;
    @Autowired UserRepository repo;
    @Autowired EmailWhitelistRepository whitelist;
    @Autowired OutboxRepository outbox;
    @Autowired TestActivationSpy mailSpy;
    @Autowired ObjectMapper mapper;

    private String emailUnico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID() + "@utn.edu.ar";
    }

    /**
     * MySQL normaliza el JSON al guardarlo (agrega espacios despues de ":" y
     * ","), asi que el string leido con findAll() no es byte a byte el que
     * escribio Jackson al publicar. Comparar por substring literal es fragil;
     * parseamos el envelope de verdad.
     */
    private boolean outboxTieneAlumnoRegistrado(String userId) {
        return outbox.findAll().stream().anyMatch(e -> {
            try {
                var nodo = mapper.readTree(e.getPayload());
                return "ALUMNO_REGISTRADO".equals(nodo.path("eventType").asText())
                        && userId.equals(nodo.path("payload").path("userId").asText());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    @Test
    void alta_de_alumno_queda_PENDING_EMAIL_y_no_publica_ALUMNO_REGISTRADO_todavia() {
        String email = emailUnico("alta");
        registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", "v1");

        String userId = repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId().toString();
        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_EMAIL);

        // DEC-34: el mail de activacion ya se encolo, pero el evento de negocio
        // (identificado por el userId de ESTA cuenta) todavia no existe.
        assertThat(outboxTieneAlumnoRegistrado(userId)).isFalse();
    }

    @Test
    void al_activar_pasa_a_PENDING_COURSE_y_recien_ahi_publica_ALUMNO_REGISTRADO() {
        String email = emailUnico("evento");
        registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", "v1");
        String userId = repo.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId().toString();

        registro.activate(mailSpy.ultimoTokenActivacion());

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_COURSE);
        assertThat(outboxTieneAlumnoRegistrado(userId))
                .as("el ALUMNO_REGISTRADO tiene que existir despues de activar, con el userId de esta cuenta")
                .isTrue();
    }

    @Test
    void profesor_en_la_whitelist_se_registra_igual_que_un_alumno() {
        String email = emailUnico("prof");
        whitelist.saveAndFlush(EmailWhitelist.create(email, UUID.randomUUID()));

        registro.registrarProfesor("Juan", "Diaz", email, "passwordvalida1", "v1");

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).get()
                .extracting(u -> u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_EMAIL);
    }

    @Test
    void profesor_fuera_de_la_whitelist_es_rechazado() {
        String email = emailUnico("noprof");

        assertThatThrownBy(() -> registro.registrarProfesor("Juan", "Diaz", email, "passwordvalida1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void alta_sin_aceptar_los_TyC_vigentes_da_400() {
        String email = emailUnico("sintyc");

        assertThatThrownBy(() -> registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", "v0-vieja"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isEmpty();
    }

    @Test
    void una_password_que_no_cumple_la_politica_da_400() {
        String email = emailUnico("passdebil");

        assertThatThrownBy(() -> registro.registrarAlumno("Ana", "Perez", "76543", email,
                "corta", "PROG4-2026-A1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void dos_altas_con_el_mismo_email_activo_fallan_con_conflicto() {
        String email = emailUnico("repetido");
        registro.registrarAlumno("Ana", "Perez", "76543", email,
                "passwordvalida1", "PROG4-2026-A1", "v1");

        assertThatThrownBy(() -> registro.registrarAlumno("Otro", "Nombre", "11111", email,
                "passwordvalida1", "PROG4-2026-A1", "v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void el_email_se_normaliza_a_minusculas_al_registrarse() {
        String email = emailUnico("mayus");
        registro.registrarAlumno("Ana", "Perez", "76543", email.toUpperCase(),
                "passwordvalida1", "PROG4-2026-A1", "v1");

        assertThat(repo.findByEmailAndDeletedAtIsNull(email)).isPresent();
    }
}
