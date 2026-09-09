package ar.edu.utn.frc.tup.p4.usersservice.auth.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.auth.entities.ServiceClient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ServiceClientRepository extends JpaRepository<ServiceClient, UUID> {
    Optional<ServiceClient> findByClientIdAndDeletedAtIsNull(String clientId);
}
