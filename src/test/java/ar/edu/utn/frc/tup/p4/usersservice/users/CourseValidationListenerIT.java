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
    @Autowired ProcessedEventRepository processedEvents;

    private User pendingCourse(String email) {
        User u = User.create("Ana", "P", email, "$2a$12$h", Role.STUDENT, "v1");
        u.activate();                     // -> PENDING_COURSE
        return repo.saveAndFlush(u);
    }

    private String envelope(String eventId, UUID userId, String result) {
        return """
               {"eventId":"%s","eventType":"COURSE-VALIDATION-RESOLVED","eventVersion":1,
                "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
                "payload":{"userId":"%s","result":"%s","courseId":"c-1"}}
               """.formatted(eventId, userId, result);
    }

    @Test
    void the_event_moves_the_account_to_ACTIVE() {
        User u = pendingCourse("cv1@utn.edu.ar");
        listener.consume(envelope(UUID.randomUUID().toString(), u.getId(), "VALIDADO_PADRON"));

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void reprocessing_the_SAME_eventId_is_a_verifiable_no_op() {
        // DEC-13, DoD criterion #6.
        User u = pendingCourse("cv2@utn.edu.ar");
        String id = UUID.randomUUID().toString();

        listener.consume(envelope(id, u.getId(), "VALIDADO_PADRON"));
        long processedBefore = processedEvents.count();
        listener.consume(envelope(id, u.getId(), "VALIDADO_PADRON"));   // again

        assertThat(processedEvents.count()).isEqualTo(processedBefore);
        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void an_event_for_an_ALREADY_ACTIVE_account_does_not_fail() {
        User u = pendingCourse("cv3@utn.edu.ar");
        u.activateAfterCourseValidation();
        repo.saveAndFlush(u);

        listener.consume(envelope(UUID.randomUUID().toString(), u.getId(), "VALIDADO_EXCEPCION"));

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void unknown_payload_fields_do_NOT_break_processing() {
        // DEC-34: if Courses sends three extra fields, we do not break.
        User u = pendingCourse("cv4@utn.edu.ar");
        String withExtraFields = """
              {"eventId":"%s","eventType":"COURSE-VALIDATION-RESOLVED","eventVersion":1,
               "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
               "campoNuevoDeCursos":"loquesea",
               "payload":{"userId":"%s","result":"VALIDADO_PADRON","courseId":"c-1",
                          "otroCampoNuevo":42}}
              """.formatted(UUID.randomUUID(), u.getId());

        listener.consume(withExtraFields);

        assertThat(repo.findById(u.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void result_and_courseId_are_NOT_persisted() {
        // DEC-09: Courses owns that data. Duplicating it here would be a second
        // source of truth, which is exactly what the v5 revision fixed.
        User u = pendingCourse("cv5@utn.edu.ar");
        listener.consume(envelope(UUID.randomUUID().toString(), u.getId(), "VALIDADO_EXCEPCION"));

        assertThat(repo.findById(u.getId()).orElseThrow().toString())
                .doesNotContain("VALIDADO_EXCEPCION").doesNotContain("c-1");
    }

    @Test
    void anUnknownEventTypeIsIgnoredSafely() {
        User user = pendingCourse("cv6@utn.edu.ar");
        long processedBefore = processedEvents.count();
        String unknownEvent = """
                {"eventId":"%s","eventType":"COURSE-ARCHIVED","eventVersion":1,
                 "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
                 "payload":{"userId":"%s","result":"ARCHIVED","courseId":"c-1"}}
                """.formatted(UUID.randomUUID(), user.getId());

        listener.consume(unknownEvent);

        assertThat(repo.findById(user.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.PENDING_COURSE);
        assertThat(processedEvents.count()).isEqualTo(processedBefore);
    }

    @Test
    void anUnknownEventVersionIsIgnoredSafely() {
        User user = pendingCourse("cv7@utn.edu.ar");
        long processedBefore = processedEvents.count();
        String unknownVersion = """
                {"eventId":"%s","eventType":"COURSE-VALIDATION-RESOLVED","eventVersion":2,
                 "timestamp":"2026-09-07T12:00:00Z","producer":"tema-02-cursos",
                 "payload":{"userId":"%s","result":"VALIDATED","courseId":"c-1"}}
                """.formatted(UUID.randomUUID(), user.getId());

        listener.consume(unknownVersion);

        assertThat(repo.findById(user.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.PENDING_COURSE);
        assertThat(processedEvents.count()).isEqualTo(processedBefore);
    }
}
