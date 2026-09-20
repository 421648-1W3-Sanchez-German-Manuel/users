package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.AuditedTable;
import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.BaseSoftDeletableEntity;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "email_whitelist")
@AuditedTable
public class EmailWhitelist extends BaseSoftDeletableEntity {

    private String email;
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;
    @Column(name = "added_by", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID addedBy;

    protected EmailWhitelist() { }

    public static EmailWhitelist create(String email, Role role, UUID addedBy) {
        EmailWhitelist w = new EmailWhitelist();
        w.setId(UUID.randomUUID());
        w.email = email.toLowerCase(Locale.ROOT);
        w.role = role;
        w.addedBy = addedBy;
        Instant now = Instant.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        return w;
    }

    public void remove() { setDeletedAt(Instant.now()); }

    public String getEmail() { return email; }
    public Role getRole() { return role; }
}
