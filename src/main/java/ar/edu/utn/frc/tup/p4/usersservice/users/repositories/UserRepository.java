package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    long countByRoleAndDeletedAtIsNull(Role role);

    /**
     * DEC-20 rule 5: InnoDB runs at REPEATABLE READ. Without FOR UPDATE, two
     * concurrent ADMIN deactivations each count two ADMINs in their own
     * snapshot and both proceed, leaving the platform with none.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select count(u) from User u where u.role = :role and u.deletedAt is null")
    long countActiveWithLock(@Param("role") Role role);
}
