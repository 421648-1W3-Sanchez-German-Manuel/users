package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Shared shape for the two self-registration routes gated by the whitelist: PROFESSOR and GESTOR. */
public record StaffRegistrationRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                      @NotBlank @Email String email, @NotBlank String password,
                                      @NotBlank String termsVersion) { }
