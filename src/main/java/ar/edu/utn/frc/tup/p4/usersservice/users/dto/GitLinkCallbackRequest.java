package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.NotBlank;

/** What the SPA forwards from the provider redirect: the grant and our state. */
public record GitLinkCallbackRequest(@NotBlank String code, @NotBlank String state) { }
