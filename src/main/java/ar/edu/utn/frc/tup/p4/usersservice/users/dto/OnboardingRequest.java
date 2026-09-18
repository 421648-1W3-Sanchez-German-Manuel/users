package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.AssertTrue;

/** DEC-GL-11: onboarding body is only the tour confirmation. */
public record OnboardingRequest(@AssertTrue boolean tourOk) { }
