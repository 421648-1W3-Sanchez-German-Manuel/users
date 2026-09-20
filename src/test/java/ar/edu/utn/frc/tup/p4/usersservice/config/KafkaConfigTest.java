package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.CourseValidationResolvedPayload;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.util.RawValue;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

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
    void producerSettingPreservesStoredJsonWithoutTypeHeaders() {
        JsonSerializer<Object> serializer = new JsonSerializer<>(objectMapper);
        serializer.configure(Map.of(JsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
        RecordHeaders headers = new RecordHeaders();
        String storedJson = "{\"eventType\":\"STUDENT-REGISTERED\",\"amount\":1.2300e+4}";

        byte[] serialized = serializer.serialize(
                "user-events",
                headers,
                new RawValue(storedJson));

        assertThat(new String(serialized, StandardCharsets.UTF_8)).isEqualTo(storedJson);
        assertThat(headers.lastHeader("__TypeId__")).isNull();
        assertThat(headers.lastHeader("__ContentTypeId__")).isNull();
    }

    @Test
    void malformedEnvelopeIsCapturedAsADeserializationError() {
        String json = """
                {
                  "eventType": "COURSE-VALIDATION-RESOLVED",
                  "eventVersion": 1,
                  "timestamp": "2026-09-13T23:35:00Z",
                  "producer": "tema-02-cursos",
                  "payload": {}
                }
                """;
        RecordHeaders headers = new RecordHeaders();
        ErrorHandlingDeserializer<EventEnvelope<CourseValidationResolvedPayload>> deserializer =
                new ErrorHandlingDeserializer<>(
                        KafkaConfig.courseValidationDeserializer(objectMapper));

        EventEnvelope<CourseValidationResolvedPayload> envelope = deserializer.deserialize(
                "course-events",
                headers,
                json.getBytes(StandardCharsets.UTF_8));

        assertThat(envelope).isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER))
                .isNotNull();
    }
}
