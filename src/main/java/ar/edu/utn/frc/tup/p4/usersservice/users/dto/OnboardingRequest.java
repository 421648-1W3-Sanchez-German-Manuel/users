package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;

/** DEC-GL-11: onboarding body is only the tour confirmation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardingRequest(@AssertTrue boolean tourOk) { }
