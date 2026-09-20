package ar.edu.utn.frc.tup.p4.usersservice.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;

@MappedSuperclass
public abstract class BaseSoftDeletableEntity extends BaseAuditableEntity {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Instant getDeletedAt() {
        return deletedAt;
    }

    protected void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
