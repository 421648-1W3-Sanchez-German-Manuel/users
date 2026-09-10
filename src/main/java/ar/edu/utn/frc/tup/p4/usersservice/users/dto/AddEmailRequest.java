package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AddEmailRequest(@NotBlank @Email String email) { }
