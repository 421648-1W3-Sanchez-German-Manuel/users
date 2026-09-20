package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.CourseValidationResolvedPayload;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void configuredDeserializerIgnoresTypeHeadersAndReadsUnknownEventPayloads() {
        String json = """
                {
                  "eventId": "3c27427f-e190-4d15-bc76-a110de54b14f",
                  "eventType": "COURSE-ARCHIVED",
                  "eventVersion": 1,
                  "timestamp": "2026-09-13T23:35:00Z",
                  "producer": "tema-02-cursos",
                  "payload": {"archivedAt": "2026-09-13T23:34:00Z"}
                }
                """;
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "java.util.LinkedHashMap".getBytes(StandardCharsets.UTF_8));

        EventEnvelope<CourseValidationResolvedPayload> envelope =
                KafkaConfig.courseValidationDeserializer(objectMapper)
                        .deserialize(
                                "course-events",
                                headers,
                                json.getBytes(StandardCharsets.UTF_8));

        assertThat(envelope.eventType()).isEqualTo("COURSE-ARCHIVED");
        assertThat(envelope.payload().userId()).isNull();
    }

    @Test
    void producerSettingPreventsMapTypeHeaders() {
        JsonSerializer<Object> serializer = new JsonSerializer<>(objectMapper);
        serializer.configure(Map.of(JsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
        RecordHeaders headers = new RecordHeaders();

        serializer.serialize("user-events", headers, Map.of("eventType", "STUDENT-REGISTERED"));

        assertThat(headers.lastHeader("__TypeId__")).isNull();
        assertThat(headers.lastHeader("__ContentTypeId__")).isNull();
    }
}
