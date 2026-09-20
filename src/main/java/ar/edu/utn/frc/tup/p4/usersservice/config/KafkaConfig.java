package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.CourseValidationResolvedPayload;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * DEC-34 - the incoming Kafka boundary. CourseValidationListener receives a
 * typed {@link EventEnvelope}{@link CourseValidationResolvedPayload} via
 * {@link JsonDeserializer}, matching the platform Kafka contract.
 *
 * The factory is built here rather than tweaking the auto-configured one so
 * that {@code users.kafka.listener-auto-startup} can gate container startup: the
 * integration suite has no broker and invokes the listener directly, so it sets
 * the flag to false to keep the container from looping on a refused connection.
 * In production the property is absent and defaults to true.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<CourseValidationResolvedPayload>>
            kafkaListenerContainerFactory(
                    @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
                    @Value("${spring.kafka.consumer.group-id:users-service}") String groupId,
                    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset,
                    @Value("${users.kafka.listener-auto-startup:true}") boolean autoStartup,
                    ObjectMapper objectMapper) {

        JsonDeserializer<EventEnvelope<CourseValidationResolvedPayload>> valueDeserializer =
                new JsonDeserializer<>(
                        new TypeReference<EventEnvelope<CourseValidationResolvedPayload>>() {
                        },
                        objectMapper);
        valueDeserializer.addTrustedPackages("ar.edu.utn.frc.tup.p4.usersservice");
        valueDeserializer.setUseTypeHeaders(false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);

        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<CourseValidationResolvedPayload>>
                factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                valueDeserializer));
        factory.setAutoStartup(autoStartup);
        return factory;
    }
}
