package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/** Configurable draft contract for course-validation events consumed from Courses. */
@ConfigurationProperties(prefix = "users.kafka.course-validation")
public record CourseValidationContractProperties(
        @Name("event-type") String eventType,
        @Name("event-version") int eventVersion,
        String producer,
        PayloadFields payload) {

    public record PayloadFields(
            @Name("user-id") String userId,
            String result,
            @Name("course-id") String courseId) {
    }
}
