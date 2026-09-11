package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;

import java.time.Instant;

public record UserMeResponse(String id, String firstNames, String lastNames, String legajo,
                             String email, Role role, AccountStatus accountStatus,
                             boolean mustChangePassword, boolean firstLogin,
                             boolean guidedTourCompleted, String githubUsername,
                             String avatarRef, boolean emailVerified, Instant createdAt,
                             Instant termsAcceptedAt, String termsVersion) { }