package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    @Test
    void createsTheRequiredVersionedEnvelope() {
        EventEnvelope<Map<String, String>> envelope =
                EventEnvelope.create("STUDENT-REGISTERED", 1, Map.of("userId", "user-1"));

        assertThat(envelope.eventId()).isInstanceOf(UUID.class);
        assertThat(envelope.eventType()).isEqualTo("STUDENT-REGISTERED");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.timestamp()).isBeforeOrEqualTo(Instant.now());
        assertThat(envelope.producer()).isEqualTo("tema-01-users");
        assertThat(envelope.payload()).containsEntry("userId", "user-1");
    }

    @Test
    void rejectsEventTypesThatDoNotFollowThePlatformConvention() {
        assertThatThrownBy(() -> EventEnvelope.create("STUDENT_REGISTERED", 1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase words separated by hyphens");
    }

    @Test
    void rejectsNonPositiveContractVersions() {
        assertThatThrownBy(() -> EventEnvelope.create("STUDENT-REGISTERED", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");
    }
}
