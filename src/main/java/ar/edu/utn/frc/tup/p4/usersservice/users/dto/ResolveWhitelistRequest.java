package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

/** rejectionReason is required only when approve is false (WhitelistRequest.reject() enforces it). */
public record ResolveWhitelistRequest(boolean approve, String rejectionReason) { }
