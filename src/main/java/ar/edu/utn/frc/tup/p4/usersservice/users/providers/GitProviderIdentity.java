package ar.edu.utn.frc.tup.p4.usersservice.users.providers;

/** The only thing this service needs to know about an external identity. DEC-GL-21. */
public record GitProviderIdentity(String externalUserId, String username) { }
