package ar.edu.utn.frc.tup.p4.usersservice.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseAuditableEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_user", updatable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID createdUser;

    @Column(name = "created_service", updatable = false, length = 100)
    private String createdService;

    @Column(name = "created_trace_id", updatable = false, length = 32)
    private String createdTraceId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_updated_user", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID lastUpdatedUser;

    @Column(name = "last_updated_service", length = 100)
    private String lastUpdatedService;

    @Column(name = "last_updated_trace_id", length = 32)
    private String lastUpdatedTraceId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedUser() {
        return createdUser;
    }

    public String getCreatedService() {
        return createdService;
    }

    public String getCreatedTraceId() {
        return createdTraceId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getLastUpdatedUser() {
        return lastUpdatedUser;
    }

    public String getLastUpdatedService() {
        return lastUpdatedService;
    }

    public String getLastUpdatedTraceId() {
        return lastUpdatedTraceId;
    }

    public long getLockVersion() {
        return lockVersion;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    protected void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    protected void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    final void initializeAuditFields(
            Instant now,
            UUID actorUser,
            String actorService,
            String traceId) {
        this.createdAt = now;
        this.createdUser = actorUser;
        this.createdService = actorService;
        this.createdTraceId = traceId;
        this.updatedAt = now;
        this.lastUpdatedUser = actorUser;
        this.lastUpdatedService = actorService;
        this.lastUpdatedTraceId = traceId;
        this.lockVersion = 0;
    }

    final void updateAuditFields(
            Instant now,
            UUID actorUser,
            String actorService,
            String traceId) {
        this.updatedAt = now;
        this.lastUpdatedUser = actorUser;
        this.lastUpdatedService = actorService;
        this.lastUpdatedTraceId = traceId;
    }
}
