package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "email_whitelist")
public class EmailWhitelist {

    @Id @Column(columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    private String email;
    @Column(name = "added_by", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID addedBy;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "deleted_at") private Instant deletedAt;

    protected EmailWhitelist() { }

    public static EmailWhitelist create(String email, UUID addedBy) {
        EmailWhitelist w = new EmailWhitelist();
        w.id = UUID.randomUUID();
        w.email = email.toLowerCase(Locale.ROOT);
        w.addedBy = addedBy;
        w.createdAt = Instant.now();
        return w;
    }

    public void remove() { this.deletedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}
