package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.EmailWhitelist;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EmailWhitelistRepository extends JpaRepository<EmailWhitelist, UUID> {
    boolean existsByEmailAndDeletedAtIsNull(String email);
    boolean existsByEmailAndRoleAndDeletedAtIsNull(String email, Role role);
    List<EmailWhitelist> findAllByDeletedAtIsNull();
    List<EmailWhitelist> findAllByDeletedAtIsNullOrderByCreatedAtDesc();
}
