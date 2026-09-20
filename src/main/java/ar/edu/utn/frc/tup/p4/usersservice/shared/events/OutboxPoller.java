package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxStatus;
import com.fasterxml.jackson.databind.util.RawValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEC-45b - publishes pending outbox rows and leaves failures pending for retry.
 */
@Component
@ConditionalOnProperty(
        prefix = "users.outbox",
        name = "poller-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxPoller {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;
    static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, Object> kafka;

    public OutboxPoller(
            OutboxRepository outbox,
            KafkaTemplate<String, Object> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${users.outbox.polling-delay:PT3S}")
    @Transactional
    public void publishPending() {
        var pendingEvents = outbox.takeByStatus(OutboxStatus.PENDING, Limit.of(BATCH_SIZE));
        for (var event : pendingEvents) {
            try {
                kafka.send(
                        event.getDestinationTopic(),
                        event.getMessageKey(),
                        new RawValue(event.getPayload())).get();
                event.markPublished();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOG.warn(
                        "OUTBOX_PUBLISH_INTERRUPTED eventId={} topic={}",
                        event.getEventId(),
                        event.getDestinationTopic());
                return;
            } catch (Exception exception) {
                event.recordFailedAttempt(MAX_ATTEMPTS);
                LOG.warn(
                        "OUTBOX_PUBLISH_FAILED eventId={} topic={} attempts={} status={}",
                        event.getEventId(),
                        event.getDestinationTopic(),
                        event.getAttempts(),
                        event.getStatus(),
                        exception);
            }
        }
    }
}
