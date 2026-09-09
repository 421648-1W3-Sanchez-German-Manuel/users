package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

/** The contract from section 06 of the gateway manifesto. Do not invent new names. */
public final class IdentityHeaders {
    public static final String PRINCIPAL_TYPE = "X-Principal-Type";
    public static final String USER_ID        = "X-User-Id";
    public static final String USER_ROLES     = "X-User-Roles";
    public static final String SERVICE_ID     = "X-Service-Id";
    public static final String SERVICE_SCOPES = "X-Service-Scopes";
    public static final String REQUEST_ID     = "X-Request-Id";

    private IdentityHeaders() { }
}
