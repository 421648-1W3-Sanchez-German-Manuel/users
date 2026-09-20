package ar.edu.utn.frc.tup.p4.usersservice.users.repositories;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.GitProviderLink;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitProviderLinkRepository extends JpaRepository<GitProviderLink, UUID> {

    List<GitProviderLink> findByUserIdAndDeletedAtIsNull(UUID userId);

    boolean existsByUserIdAndProviderAndDeletedAtIsNull(UUID userId, GitProvider provider);

    /** DEC-20 rule 5: check-then-act under FOR UPDATE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GitProviderLink> findByUserIdAndProviderAndDeletedAtIsNull(UUID userId, GitProvider provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GitProviderLink> findByProviderAndExternalUserIdAndDeletedAtIsNull(
            GitProvider provider, String externalUserId);
}
