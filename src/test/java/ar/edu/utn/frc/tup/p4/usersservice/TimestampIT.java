package ar.edu.utn.frc.tup.p4.usersservice;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-20 regla 2 · criterio de DoD #21. Con TIMESTAMP, dos instancias con
 * different timezones would store different values for the same instant.
 * Con DATETIME(6) + UTC en la conexion, no.
 */
class TimestampIT extends AbstractIntegrationTest {

    @Autowired UserRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void un_Instant_sobrevive_a_un_cambio_de_timezone_del_proceso() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Cordoba"));
            User u = repo.saveAndFlush(
                    User.create("A", "A", "tz@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
            String guardado = jdbc.queryForObject(
                    "SELECT created_at FROM users WHERE id = ?", String.class, u.getId().toString());

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String releido = jdbc.queryForObject(
                    "SELECT created_at FROM users WHERE id = ?", String.class, u.getId().toString());

            // The value in the database does not change with who reads it.
            assertThat(releido).isEqualTo(guardado);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void los_timestamps_tienen_precision_de_microsegundos() {
        User u = repo.saveAndFlush(
                User.create("B", "B", "us@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1"));
        Integer escala = jdbc.queryForObject(
                "SELECT datetime_precision FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name='users' AND column_name='created_at'",
                Integer.class);
        assertThat(escala).isEqualTo(6);
        assertThat(u.getDeletedAt()).isNull();
    }

    @Test
    void el_sobre_de_eventos_usa_ISO_8601_UTC() {
        // DEC-12: one single representation of time across the whole system.
        String ts = ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope
                .de("X", "y").timestamp();
        assertThat(ts).endsWith("Z");
        assertThat(Instant.parse(ts)).isNotNull();
    }
}
