package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetConfirmRequest(@NotBlank String token, @NotBlank String newPassword) { }