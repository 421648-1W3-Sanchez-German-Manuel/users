package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.NotBlank;

/** DEC-30 - avatarRef is OPTIONAL: no @NotBlank. */
public record OnboardingRequest(@NotBlank String githubUsername, String avatarRef, boolean tourOk) { }
