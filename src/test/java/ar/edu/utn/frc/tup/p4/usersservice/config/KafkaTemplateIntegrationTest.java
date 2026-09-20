package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = KafkaAutoConfiguration.class)
@ActiveProfiles("test")
@EmbeddedKafka(
        topics = KafkaTemplateIntegrationTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class KafkaTemplateIntegrationTest {

    static final String TOPIC = "producer-contract-test";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void autoConfiguredTemplatePublishesJsonWithoutTypeHeaders() throws Exception {
        Map<String, Object> consumerProperties =
                KafkaTestUtils.consumerProps("producer-contract-test", "false", broker);
        try (Consumer<String, byte[]> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProperties,
                new StringDeserializer(),
                new ByteArrayDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);

            kafkaTemplate.send(TOPIC, "user-1", Map.of("eventType", "STUDENT-REGISTERED")).get();

            var record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
            assertThat(record.headers().lastHeader("__ContentTypeId__")).isNull();
            assertThat(new String(record.value(), StandardCharsets.UTF_8))
                    .contains("\"eventType\":\"STUDENT-REGISTERED\"");
        }
    }
}
