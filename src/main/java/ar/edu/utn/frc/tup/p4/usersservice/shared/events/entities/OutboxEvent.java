package ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * DEC-45b - Outbox. The event is INSERTED in the same transaction as the
 * state change that produced it. If the transaction rolls back the event does
 * not exist: you cannot announce something that did not happen.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id @Column(name = "event_id")
    private String eventId;

    private String topic;

    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(name = "created_at")   private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "attempts")
    private int attempts;

    protected OutboxEvent() { }

    public static OutboxEvent pending(String eventId, String topic, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = eventId;
        e.topic = topic;
        e.payload = payload;
        e.createdAt = Instant.now();
        e.attempts = 0;
        return e;
    }

    public void markPublished() { this.publishedAt = Instant.now(); }
    public void recordFailedAttempt() { this.attempts++; }

    public String getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
}
