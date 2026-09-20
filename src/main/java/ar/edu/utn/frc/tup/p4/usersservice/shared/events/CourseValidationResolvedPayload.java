package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of {@code COURSE-VALIDATION-RESOLVED} as consumed from Courses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseValidationResolvedPayload(
        String userId,
        String result,
        String courseId) {
}
