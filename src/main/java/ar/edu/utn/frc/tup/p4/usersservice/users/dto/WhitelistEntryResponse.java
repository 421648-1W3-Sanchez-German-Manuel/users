package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;

import java.time.Instant;

/** Row of the authorized-emails list. */
public record WhitelistEntryResponse(String id, String email, Role role, Instant createdAt) { }
