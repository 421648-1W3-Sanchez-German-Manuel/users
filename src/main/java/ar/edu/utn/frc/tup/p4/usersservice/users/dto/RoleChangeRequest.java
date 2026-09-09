package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.validation.constraints.NotNull;

public record RoleChangeRequest(@NotNull Role role) { }
