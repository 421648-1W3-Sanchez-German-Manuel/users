package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> { }
