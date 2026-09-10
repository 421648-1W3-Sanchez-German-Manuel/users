package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.ProcessedEventRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.listeners.CourseValidationListener;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CourseValidationListenerIT extends AbstractIntegrationTest {

    @Autowired CourseValidationListener listener;
    @Autowired UserRepository repo;
    @Autowired ProcessedEventRepository procesados;

    private User pendienteCurso(String email) {
        User u = User.create("Ana", "P", email, "$2a$12$h", Role.STUDENT, "v1");
        u.activate();                     // -> PENDING_COURSE
        return repo.saveAndFlush(u);
    }

    private String sobre(String eventId, UUID userId, String resultado) {
        return """
               {"eventId":"%s","eventType":"VALIDACION_CURSO_RESUELTA",
                "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
                "payload":{"userId":"%s","resultado":"%s","cursoId":"c-1"}}
               """.formatted(eventId, userId, resultado);
    }

    @Test
    void el_evento_pasa_la_cuenta_a_ACTIVA() {
        User u = pendienteCurso("cv1@utn.edu.ar");
        listener.consumir(sobre(UUID.randomUUID().toString(), u.getId(), "VALIDADO_PADRON"));

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void reprocesar_el_MISMO_eventId_es_un_no_op_verificable() {
        // DEC-13 · criterio de DoD #6.
        User u = pendienteCurso("cv2@utn.edu.ar");
        String id = UUID.randomUUID().toString();

        listener.consumir(sobre(id, u.getId(), "VALIDADO_PADRON"));
        long procesadosAntes = procesados.count();
        listener.consumir(sobre(id, u.getId(), "VALIDADO_PADRON"));   // otra vez

        assertThat(procesados.count()).isEqualTo(procesadosAntes);
        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void un_evento_sobre_una_cuenta_YA_ACTIVA_no_falla() {
        User u = pendienteCurso("cv3@utn.edu.ar");
        u.activateAfterCourseValidation();
        repo.saveAndFlush(u);

        listener.consumir(sobre(UUID.randomUUID().toString(), u.getId(), "VALIDADO_EXCEPCION"));

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void campos_desconocidos_en_el_payload_NO_rompen() {
        // DEC-34: if Cursos sends three extra fields, we do not break.
        User u = pendienteCurso("cv4@utn.edu.ar");
        String conExtras = """
              {"eventId":"%s","eventType":"VALIDACION_CURSO_RESUELTA",
               "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
               "campoNuevoDeCursos":"loquesea",
               "payload":{"userId":"%s","resultado":"VALIDADO_PADRON","cursoId":"c-1",
                          "otroCampoNuevo":42}}
              """.formatted(UUID.randomUUID(), u.getId());

        listener.consumir(conExtras);

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void el_resultado_y_el_cursoId_NO_se_persisten() {
        // DEC-09: Cursos owns that data. Duplicating it here would be a second
        // source of truth, which is exactly what the v5 revision fixed.
        User u = pendienteCurso("cv5@utn.edu.ar");
        listener.consumir(sobre(UUID.randomUUID().toString(), u.getId(), "VALIDADO_EXCEPCION"));

        assertThat(repo.findById(u.getId()).orElseThrow().toString())
                .doesNotContain("VALIDADO_EXCEPCION").doesNotContain("c-1");
    }
}
