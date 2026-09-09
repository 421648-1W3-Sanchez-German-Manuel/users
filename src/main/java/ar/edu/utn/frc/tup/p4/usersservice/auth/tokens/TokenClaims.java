package ar.edu.utn.frc.tup.p4.usersservice.auth.tokens;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import com.nimbusds.jwt.JWTClaimsSet;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Builds the closed claim sets for person and service tokens. */
public final class TokenClaims {

    private final String type;
    private final String subject;
    private final List<String> roles;
    private final String sessionId;
    private final String accountStatus;
    private final Boolean passwordChangeRequired;
    private final Boolean onboardingPending;
    private final String audience;
    private final String scope;
    private final String onBehalfOf;
    private final String jwtId;

    private TokenClaims(Builder builder) {
        this.type = builder.type;
        this.subject = builder.subject;
        this.roles = builder.roles;
        this.sessionId = builder.sessionId;
        this.accountStatus = builder.accountStatus;
        this.passwordChangeRequired = builder.passwordChangeRequired;
        this.onboardingPending = builder.onboardingPending;
        this.audience = builder.audience;
        this.scope = builder.scope;
        this.onBehalfOf = builder.onBehalfOf;
        this.jwtId = builder.jwtId;
    }

    public static Builder paraPersona(
            UUID subject,
            List<Role> roles,
            String sessionId,
            AccountStatus accountStatus,
            boolean passwordChangeRequired,
            boolean onboardingPending) {
        Builder builder = new Builder("user", Objects.requireNonNull(subject, "subject").toString());
        builder.roles = Objects.requireNonNull(roles, "roles").stream().map(Enum::name).toList();
        builder.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        builder.accountStatus = Objects.requireNonNull(accountStatus, "accountStatus").name();
        builder.passwordChangeRequired = passwordChangeRequired;
        builder.onboardingPending = onboardingPending;
        return builder;
    }

    public static Builder paraServicio(String clientId, String audience, Set<String> scopes) {
        Builder builder = new Builder("service", Objects.requireNonNull(clientId, "clientId"));
        builder.roles = List.of("MS");
        builder.audience = Objects.requireNonNull(audience, "audience");
        builder.scope = String.join(" ", new TreeSet<>(Objects.requireNonNull(scopes, "scopes")));
        if (builder.scope.isBlank()) {
            throw new IllegalArgumentException("A service token requires at least one scope");
        }
        return builder;
    }

    public JWTClaimsSet aClaimsSet(String issuer, Duration lifetime) {
        Instant issuedAt = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(Objects.requireNonNull(issuer, "issuer"))
                .subject(subject)
                .claim("roles", roles)
                .claim("type", type)
                .jwtID(jwtId)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(Objects.requireNonNull(lifetime, "lifetime"))));

        if ("user".equals(type)) {
            claims.claim("sid", sessionId)
                    .claim("est", accountStatus)
                    .claim("pwd", passwordChangeRequired)
                    .claim("onb", onboardingPending);
        } else {
            claims.audience(audience).claim("scope", scope);
            if (onBehalfOf != null) {
                claims.claim("on_behalf_of", onBehalfOf);
            }
        }

        return claims.build();
    }

    public String jti() {
        return jwtId;
    }

    public static final class Builder {

        private final String type;
        private final String subject;
        private List<String> roles;
        private String sessionId;
        private String accountStatus;
        private Boolean passwordChangeRequired;
        private Boolean onboardingPending;
        private String audience;
        private String scope;
        private String onBehalfOf;
        private String jwtId = UUID.randomUUID().toString();

        private Builder(String type, String subject) {
            this.type = type;
            this.subject = subject;
        }

        public Builder conOnBehalfOf(UUID actor) {
            if (!"service".equals(type)) {
                throw new IllegalStateException("on_behalf_of only applies to service tokens");
            }
            this.onBehalfOf = actor == null ? null : actor.toString();
            return this;
        }

        public Builder conJti(String jwtId) {
            this.jwtId = Objects.requireNonNull(jwtId, "jwtId");
            return this;
        }

        public TokenClaims build() {
            return new TokenClaims(this);
        }
    }
}
