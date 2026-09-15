package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * `role` is a raw, OPTIONAL string — not the enum — for two reasons: an invalid
 * enum value would otherwise fail Jackson deserialization before Bean Validation
 * runs (a generic "body is not valid JSON" 400 instead of an explicit message),
 * and clients that predate GESTOR (e.g. scripts/regresion.sh) still send
 * email-only payloads. The controller parses it (defaulting to PROFESSOR when
 * absent) and WhitelistService validates it is PROFESSOR/GESTOR — the whitelist
 * never grants ADMIN.
 */
public record AddEmailRequest(@NotBlank @Email String email, String role) { }
