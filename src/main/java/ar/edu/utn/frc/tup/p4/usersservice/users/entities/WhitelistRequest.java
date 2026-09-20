package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.AuditedTable;
import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.BaseAuditableEntity;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** DEC-29 - a PROFESSOR's request to add an e-mail to the whitelist. */
@Entity
@Table(name = "whitelist_requests")
@AuditedTable
public class WhitelistRequest extends BaseAuditableEntity {

    @Column(name = "requested_email") private String requestedEmail;
    @Column(name = "requested_by", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID requestedBy;
    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private RequestStatus status;

    @Column(name = "resolved_by", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID resolvedBy;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "resolved_at") private Instant resolvedAt;

    protected WhitelistRequest() { }

    public static WhitelistRequest create(String email, UUID requestedBy, String reason) {
        WhitelistRequest r = new WhitelistRequest();
        r.setId(UUID.randomUUID());
        r.requestedEmail = email.toLowerCase(Locale.ROOT);
        r.requestedBy = requestedBy;
        r.reason = reason;
        r.status = RequestStatus.PENDING;
        Instant now = Instant.now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    public void approve(UUID admin) {
        requirePending();
        this.status = RequestStatus.APPROVED;
        this.resolvedBy = admin;
        this.resolvedAt = Instant.now();
    }

    public void reject(UUID admin, String rejectionReason) {
        requirePending();
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required");
        }
        this.status = RequestStatus.REJECTED;
        this.resolvedBy = admin;
        this.rejectionReason = rejectionReason;
        this.resolvedAt = Instant.now();
    }

    private void requirePending() {
        if (this.status != RequestStatus.PENDING) {
            throw new IllegalStateException("The request has already been resolved: " + this.status);
        }
    }

    public String getRequestedEmail() { return requestedEmail; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getReason() { return reason; }
    public RequestStatus getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
}
