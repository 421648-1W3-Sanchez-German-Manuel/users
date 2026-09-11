package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.EmailWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EmailWhitelistRepository extends JpaRepository<EmailWhitelist, UUID> {
    boolean existsByEmailAndDeletedAtIsNull(String email);
    List<EmailWhitelist> findAllByDeletedAtIsNull();
    List<EmailWhitelist> findAllByDeletedAtIsNullOrderByCreatedAtDesc();
}
