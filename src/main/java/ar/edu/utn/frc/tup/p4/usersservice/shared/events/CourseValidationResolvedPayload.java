package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * Payload of {@code COURSE-VALIDATION-RESOLVED} as consumed from Courses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseValidationResolvedPayload(
        String userId,
        String result,
        String courseId) {

    public CourseValidationResolvedPayload {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(result, "result is required");
        Objects.requireNonNull(courseId, "courseId is required");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (result.isBlank()) {
            throw new IllegalArgumentException("result is required");
        }
        if (courseId.isBlank()) {
            throw new IllegalArgumentException("courseId is required");
        }
    }
}
