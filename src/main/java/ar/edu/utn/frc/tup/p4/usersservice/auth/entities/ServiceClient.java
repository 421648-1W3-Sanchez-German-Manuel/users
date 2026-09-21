package ar.edu.utn.frc.tup.p4.usersservice.auth.entities;

import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.AuditExclude;
import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.AuditedTable;
import ar.edu.utn.frc.tup.p4.usersservice.shared.audit.BaseSoftDeletableEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "service_clients")
@AuditedTable
public class ServiceClient extends BaseSoftDeletableEntity {

    @Column(name = "client_id")   private String clientId;
    @AuditExclude
    @Column(name = "secret_hash") private String secretHash;
    @Column(name = "description")
    private String description;

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
        c.setId(UUID.randomUUID());
        c.clientId = clientId;
        c.secretHash = secretHash;
        c.description = description;
        c.allowedScopes = new HashSet<>(scopes);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    public String getClientId() { return clientId; }
    public String getSecretHash() { return secretHash; }
    public Set<String> getAllowedScopes() { return Collections.unmodifiableSet(allowedScopes); }
}
