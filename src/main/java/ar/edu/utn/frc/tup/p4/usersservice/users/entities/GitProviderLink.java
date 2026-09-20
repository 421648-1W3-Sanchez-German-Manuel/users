package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.AuditedTable;
import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.BaseSoftDeletableEntity;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_git_provider_links")
@AuditedTable
public class GitProviderLink extends BaseSoftDeletableEntity {

    @Column(name = "user_id", columnDefinition = "CHAR(36)", nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GitProvider provider;

    /** Stable provider-side id. DEC-GL-01: never the username. */
    @Column(name = "external_user_id", nullable = false, length = 100)
    private String externalUserId;

    /** Display cache only. DEC-GL-01. */
    @Column(nullable = false, length = 100)
    private String username;

    protected GitProviderLink() { }

    public static GitProviderLink create(UUID userId, GitProvider provider,
                                         String externalUserId, String username) {
        GitProviderLink link = new GitProviderLink();
        Instant now = Instant.now();
        link.setId(UUID.randomUUID());
        link.userId = userId;
        link.provider = provider;
        link.externalUserId = externalUserId;
        link.username = username;
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        return link;
    }

    public void softDelete() {
        setDeletedAt(Instant.now());
        setUpdatedAt(Instant.now());
    }

    public UUID getUserId() { return userId; }
    public GitProvider getProvider() { return provider; }
    public String getExternalUserId() { return externalUserId; }
    public String getUsername() { return username; }
}
