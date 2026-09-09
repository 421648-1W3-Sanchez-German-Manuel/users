package ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities;

import jakarta.persistence.*;
import java.time.Instant;

/** DEC-13 - consumer-side idempotency (the mirror image of OutboxEvent). */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type")   private String eventType;
    @Column(name = "processed_at") private Instant processedAt;

    protected ProcessedEvent() { }

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
}
