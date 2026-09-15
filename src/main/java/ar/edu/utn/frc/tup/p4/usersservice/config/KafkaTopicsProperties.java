package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/** Domain-oriented Kafka topic catalogue. */
@ConfigurationProperties(prefix = "users.kafka.topics")
public record KafkaTopicsProperties(
        @Name("user-events") String userEvents,
        @Name("notification-events") String notificationEvents,
        @Name("course-events") String courseEvents) {
}
