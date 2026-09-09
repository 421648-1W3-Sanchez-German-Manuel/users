package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

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

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPoller(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "PT2S")
    @Transactional
    public void publicarPendientes() {
        outbox.takePending(Limit.of(BATCH_SIZE)).forEach(event -> {
            try {
                kafka.send(event.getTopic(), event.getEventId(), event.getPayload()).get();
                event.markPublished();
            } catch (Exception exception) {
                event.recordFailedAttempt();
                LOG.warn(
                        "OUTBOX_PUBLISH_FAILED eventId={} topic={} attempts={}",
                        event.getEventId(),
                        event.getTopic(),
                        event.getAttempts());
                Thread.currentThread().interrupt();
            }
        });
    }
}
