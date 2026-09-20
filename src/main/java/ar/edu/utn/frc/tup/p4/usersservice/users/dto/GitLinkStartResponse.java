package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

/** What `start` returns: where to send the browser for provider consent. */
public record GitLinkStartResponse(String authorizationUrl) { }
