package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * DEC-34 - the incoming Kafka boundary. The only listener is
 * CourseValidationListener; every envelope is read as a raw JSON String and
 * parsed with Jackson, so the value deserializer is StringDeserializer and no
 * type mapping is bound to the container.
 *
 * The factory is built here rather than tweaking the auto-configured one so
 * that `users.kafka.listener-auto-startup` can gate container startup: the
 * integration suite has no broker and calls `consumir` directly, so it sets the
 * flag to false to keep the container from looping on a refused connection. In
 * production the property is absent and defaults to true.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:users-service}") String groupId,
            @Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset,
            @Value("${users.kafka.listener-auto-startup:true}") boolean autoStartup) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setAutoStartup(autoStartup);
        return factory;
    }
}
