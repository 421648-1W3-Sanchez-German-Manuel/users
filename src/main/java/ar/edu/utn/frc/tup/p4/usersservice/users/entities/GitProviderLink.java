package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_git_provider_links")
public class GitProviderLink {

    @Id
    @Column(columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

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

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected GitProviderLink() { }

    public static GitProviderLink create(UUID userId, GitProvider provider,
                                         String externalUserId, String username) {
        GitProviderLink link = new GitProviderLink();
        Instant now = Instant.now();
        link.id = UUID.randomUUID();
        link.userId = userId;
        link.provider = provider;
        link.externalUserId = externalUserId;
        link.username = username;
        link.linkedAt = now;
        link.updatedAt = now;
        return link;
    }

    public void softDelete() {
        Instant now = Instant.now();
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public GitProvider getProvider() { return provider; }
    public String getExternalUserId() { return externalUserId; }
    public String getUsername() { return username; }
    public Instant getLinkedAt() { return linkedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
