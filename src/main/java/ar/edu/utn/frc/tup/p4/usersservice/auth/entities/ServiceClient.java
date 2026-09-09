package ar.edu.utn.frc.tup.p4.usersservice.auth.entities;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "service_clients")
public class ServiceClient {

    @Id @Column(columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)                // Bind as CHAR(36), not as bytes
    private UUID id;

    @Column(name = "client_id")   private String clientId;
    @Column(name = "secret_hash") private String secretHash;
    @Column(name = "description")
    private String description;
    @Column(name = "created_at")  private Instant createdAt;
    @Column(name = "deleted_at")  private Instant deletedAt;

    /** DEC-20 rule 3: a list is a child table, never a column. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "service_client_scopes",
                     joinColumns = @JoinColumn(name = "service_client_id"))
    @Column(name = "scope")
    private Set<String> allowedScopes = new HashSet<>();

    protected ServiceClient() { }

    public static ServiceClient create(String clientId, String secretHash,
                                      String description, Set<String> scopes) {
        ServiceClient c = new ServiceClient();
        c.id = UUID.randomUUID();
        c.clientId = clientId;
        c.secretHash = secretHash;
        c.description = description;
        c.allowedScopes = new HashSet<>(scopes);
        c.createdAt = Instant.now();
        return c;
    }

    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public String getSecretHash() { return secretHash; }
    public Set<String> getAllowedScopes() { return Collections.unmodifiableSet(allowedScopes); }
}
