package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-45b. Kafka does not take part in the MySQL transaction.
 */
class OutboxIT extends AbstractIntegrationTest {

    @Autowired AccountEventPublisher publisher;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;

    @Test
    @Transactional
    void publishing_writes_a_pending_row_without_publishing_to_kafka() {
        // Use a unique topic for each run and filter by it. There is ONE database
        // for all test classes and nobody cleans it between them. As soon as
        // another flow publishes an event, such as a login 2FA email or a
        // registration activation email, the outbox no longer has a single row
        // and this test would fail for an unrelated reason.
        //
        // This is the same pattern used by the second test in this class, which
        // measures a delta against `outbox.count()` instead of asserting on the
        // entire table.
        String topic = "topico.test." + UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        publisher.publish(
                topic,
                aggregateId.toString(),
                "TEST-EVENT",
                1,
                "test-aggregate",
                aggregateId,
                new Payload("value"));

        var pendingEvents = outbox.findAll().stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .filter(event -> topic.equals(event.getDestinationTopic()))
                .toList();

        assertThat(pendingEvents).hasSize(1);
        assertThat(pendingEvents.getFirst().getDestinationTopic()).isEqualTo(topic);
        assertThat(pendingEvents.getFirst().getMessageKey()).isEqualTo(aggregateId.toString());
        assertThat(pendingEvents.getFirst().getPayload()).contains("\"eventType\":\"TEST-EVENT\"");
        assertThat(pendingEvents.getFirst().getPayload()).contains("\"eventVersion\":1");
        assertThat(pendingEvents.getFirst().getPayload()).contains("\"producer\":\"tema-01-users\"");
    }

    @Test
    void a_rolled_back_transaction_does_not_leave_an_event() {
        long before = outbox.count();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID aggregateId = UUID.randomUUID();
            publisher.publish(
                    "test-events",
                    aggregateId.toString(),
                    "TEST-EVENT",
                    1,
                    "test-aggregate",
                    aggregateId,
                    new Payload("x"));
            throw new IllegalStateException("intentional rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.count()).isEqualTo(before);
    }

    record Payload(String value) {
    }
}
