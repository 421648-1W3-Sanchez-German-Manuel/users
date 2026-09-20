package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerTest {

    @Test
    void publishesWithTheStoredMessageKeyAndMarksTheEventAsPublished() {
        OutboxRepository repository = mock(OutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        UUID userId = UUID.randomUUID();
        OutboxEvent event = pendingEvent(userId);

        when(repository.takeByStatus(OutboxStatus.PENDING, Limit.of(100)))
                .thenReturn(List.of(event));
        when(kafka.send("user-events", userId.toString(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPoller(repository, kafka).publishPending();

        verify(kafka).send("user-events", userId.toString(), event.getPayload());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void marksTheEventAsFailedAfterFiveFailedAttempts() {
        OutboxRepository repository = mock(OutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        UUID userId = UUID.randomUUID();
        OutboxEvent event = pendingEvent(userId);

        when(repository.takeByStatus(OutboxStatus.PENDING, Limit.of(100)))
                .thenReturn(List.of(event));
        when(kafka.send("user-events", userId.toString(), event.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        OutboxPoller poller = new OutboxPoller(repository, kafka);
        for (int attempt = 0; attempt < OutboxPoller.MAX_ATTEMPTS; attempt++) {
            poller.publishPending();
        }

        assertThat(event.getAttempts()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getPublishedAt()).isNull();
    }

    private OutboxEvent pendingEvent(UUID userId) {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "STUDENT-REGISTERED",
                "user",
                userId,
                "user-events",
                userId.toString(),
                "{}");
    }
}
