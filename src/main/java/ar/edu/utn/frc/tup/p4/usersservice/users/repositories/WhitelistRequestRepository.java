package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.WhitelistRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface WhitelistRequestRepository extends JpaRepository<WhitelistRequest, UUID> {
    List<WhitelistRequest> findAllByStatus(RequestStatus status);
    List<WhitelistRequest> findAllByRequestedBy(UUID requestedBy);
}
