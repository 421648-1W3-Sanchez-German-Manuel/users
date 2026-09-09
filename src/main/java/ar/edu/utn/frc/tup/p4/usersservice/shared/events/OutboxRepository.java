package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * DEC-45b: SKIP LOCKED is what allows more than one instance without
     * publishing duplicates — each poller takes different rows instead of
     * blocking against the other. MySQL 8 supports it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt asc")
    List<OutboxEvent> takePending(Limit limit);
}
