package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

/** Phase 1 returns NO tokens: only the identifier of the 2FA challenge. */
public record LoginResponse(String challengeId, String message) { }