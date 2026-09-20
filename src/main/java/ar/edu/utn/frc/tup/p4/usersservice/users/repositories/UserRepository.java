package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    /** Active accounts, newest first. Named query: avoids spring-data's count-then-paged method detection. */
    List<User> findByDeletedAtIsNullOrderByCreatedAtDesc();

    /** Active accounts restricted to the given roles, newest first. Used to scope the GESTOR directory. */
    List<User> findByRoleInAndDeletedAtIsNullOrderByCreatedAtDesc(List<Role> roles);

    long countByRoleAndDeletedAtIsNull(Role role);

    /**
     * DEC-20 rule 5: InnoDB runs at REPEATABLE READ. Without FOR UPDATE, two
     * concurrent ADMIN deactivations each count two ADMINs in their own
     * snapshot and both proceed, leaving the platform with none.
     */
    /**
     * Returns the ids so the caller can count them. It does NOT
     * {@code select count(...)}, and that is the entire point: Hibernate emits
     * no {@code for update} for an aggregate query. It dropped the lock hint
     * silently, the generated SQL was a plain
     * {@code select count(u1_0.id) from users ...}, and the method kept the
     * word "lock" in its name while taking none — so the scenario the javadoc
     * above describes was live. Two ADMINs deactivating each other both counted
     * two and both proceeded, leaving zero.
     *
     * <p>Selecting rows keeps the {@code for update}, which is what makes the
     * second transaction wait and then read the post-commit count. The result
     * set is the active ADMINs of an institution: a handful of rows, so
     * counting them in Java costs nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u.id from User u where u.role = :role and u.deletedAt is null")
    List<UUID> lockActive(@Param("role") Role role);
}
