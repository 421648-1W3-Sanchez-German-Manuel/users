package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * RF-ROL-03 - creates ADMIN accounts only. PROFESSOR and STUDENT enter only
 * through the whitelist plus self-registration (RegistrationController); an
 * ADMIN creating them directly with a password would leave them in PENDING_EMAIL
 * without an activation link, making the account impossible to activate.
 */
public record CreateUserRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                  @NotBlank @Email String email, @NotBlank String password) { }
