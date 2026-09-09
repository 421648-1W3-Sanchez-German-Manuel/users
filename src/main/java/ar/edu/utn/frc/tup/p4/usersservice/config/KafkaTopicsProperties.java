package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/** DEC-34: every topic name is configuration, never a constant. */
@ConfigurationProperties(prefix = "users.kafka.topics")
public record KafkaTopicsProperties(
        @Name("audit") String auditoria,
        @Name("student-registered") String alumnoRegistrado,
        @Name("notifications") String notificaciones,
        @Name("course-validation") String validationCurso) {
}
