package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * RF-ROL-03 - solo crea ADMIN. PROFESSOR y STUDENT entran unicamente por
 * whitelist + auto-registro (RegistrationController); un ADMIN dandoles de
 * alta con password directo los dejaba en PENDING_EMAIL sin enlace de
 * activacion, una cuenta que no se podia activar nunca.
 */
public record CreateUserRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                  @NotBlank @Email String email, @NotBlank String password) { }
