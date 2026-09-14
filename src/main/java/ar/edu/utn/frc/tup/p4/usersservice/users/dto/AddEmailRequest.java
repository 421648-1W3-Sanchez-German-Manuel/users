package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** `role` is validated to PROFESSOR/GESTOR in WhitelistService — the whitelist never grants ADMIN. */
public record AddEmailRequest(@NotBlank @Email String email, @NotNull Role role) { }
