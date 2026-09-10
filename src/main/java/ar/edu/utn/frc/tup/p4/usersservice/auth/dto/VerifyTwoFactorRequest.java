package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyTwoFactorRequest(@NotBlank String challengeId,
                                    @NotBlank @Pattern(regexp = "\\d{6}") String code) { }