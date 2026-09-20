package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerTest {

    @Test
    void publishesWithTheStoredMessageKeyAndMarksTheEventAsPublished() {
        OutboxRepository repository = mock(OutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        UUID userId = UUID.randomUUID();
        String payload = "{\"eventType\":\"STUDENT-REGISTERED\",\"eventVersion\":1}";
        OutboxEvent event = pendingEvent(userId, payload);

        when(repository.takeByStatus(OutboxStatus.PENDING, Limit.of(100)))
                .thenReturn(List.of(event));
        when(kafka.send(eq("user-events"), eq(userId.toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPoller(repository, kafka, mapper).publishPending();

        verify(kafka).send(eq("user-events"), eq(userId.toString()), any(Map.class));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void marksTheEventAsFailedAfterFiveFailedAttempts() {
        OutboxRepository repository = mock(OutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        UUID userId = UUID.randomUUID();
        OutboxEvent event = pendingEvent(userId, "{}");

        when(repository.takeByStatus(OutboxStatus.PENDING, Limit.of(100)))
                .thenReturn(List.of(event));
        when(kafka.send(eq("user-events"), eq(userId.toString()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        OutboxPoller poller = new OutboxPoller(repository, kafka, mapper);
        for (int attempt = 0; attempt < OutboxPoller.MAX_ATTEMPTS; attempt++) {
            poller.publishPending();
        }

        assertThat(event.getAttempts()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getPublishedAt()).isNull();
    }

    private OutboxEvent pendingEvent(UUID userId, String payload) {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "STUDENT-REGISTERED",
                "user",
                userId,
                "user-events",
                userId.toString(),
                payload);
    }
}
