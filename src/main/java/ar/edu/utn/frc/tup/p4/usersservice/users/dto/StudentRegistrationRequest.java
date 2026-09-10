package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record StudentRegistrationRequest(@NotBlank String firstNames, @NotBlank String lastNames,
                                    @NotBlank String legajo, @NotBlank @Email String email,
                                    @NotBlank String password, @NotBlank String invitationCode,
                                    @NotBlank String termsVersion) { }
