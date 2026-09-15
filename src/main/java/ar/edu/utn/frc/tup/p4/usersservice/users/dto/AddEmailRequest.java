package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * `role` is OPTIONAL and defaults to PROFESSOR in WhitelistService — clients that
 * predate GESTOR (e.g. scripts/regresion.sh) still send email-only payloads. It is
 * validated to PROFESSOR/GESTOR — the whitelist never grants ADMIN.
 */
public record AddEmailRequest(@NotBlank @Email String email, Role role) { }
