package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;

import java.time.Instant;

/** Row for the ADMIN's user directory. Active accounts only. */
public record UserListItemResponse(String id, String firstNames, String lastNames, String legajo,
                                   String email, Role role, AccountStatus accountStatus,
                                   Instant createdAt) { }