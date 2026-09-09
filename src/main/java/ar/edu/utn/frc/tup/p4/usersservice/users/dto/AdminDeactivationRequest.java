package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.NotBlank;

/** RF-ROL-06: `usernameConfirmation` is the written confirmation. */
public record AdminDeactivationRequest(@NotBlank String password, @NotBlank String twoFactorCode,
                               @NotBlank String usernameConfirmation) { }
