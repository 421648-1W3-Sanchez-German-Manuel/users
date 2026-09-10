package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The link's token. It does NOT carry the e-mail: the token already identifies
 * the account, and asking for it as well would be an enumeration channel. The
 * pattern narrows what is accepted to base64url of the expected length, so
 * anything that cannot possibly be a real token is rejected before touching Redis.
 */
public record ActivateAccountRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{40,64}") String token) { }
