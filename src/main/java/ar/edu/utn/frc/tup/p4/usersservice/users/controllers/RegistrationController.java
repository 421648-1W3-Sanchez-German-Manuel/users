package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Registration",
     description = "Account registration and email verification. Anonymous: no token required.")
@RestController
@RequestMapping("${app.api.public-path}/registration")
public class RegistrationController {

    private final RegistrationService registration;

    public RegistrationController(RegistrationService registration) { this.registration = registration; }

    @Operation(summary = "Student self-registration",
               description = """
                        Requires a valid `invitationCode`: a student cannot register independently.

                        The account remains in `PENDING_EMAIL` and cannot be used until `/activate`
                        is called with the token from the link sent by email.""")
    @ApiResponse(responseCode = "200", description = "Registration accepted. The activation link is sent by email.")
    @ApiResponse(responseCode = "403", description = """
            `type`: `email-not-whitelisted`. The email is not authorized for registration.""")
    @ApiResponse(responseCode = "409", description = """
            `type`: `duplicate-email`. An ACTIVE account with that email already exists.
            Uniqueness applies to active rows, not historical ones: nothing is physically
            deleted (non-negotiable 6), so an email from a deactivated account can be reused.""")
    @PostMapping("/student")
    public void registerStudent(@Valid @RequestBody StudentRegistrationRequest r) {
        registration.registerStudent(r.firstNames(), r.lastNames(), r.legajo(), r.email(),
                r.password(), r.invitationCode(), r.termsVersion());
    }

    @Operation(summary = "Professor self-registration",
               description = """
                        The email must be on the whitelist managed by an ADMIN or GESTOR.
                        Unlike a student, a professor does not join by invitation: someone must
                        authorize the email first.

                        `termsVersion` must be the current version returned by `/legal/terms`.""")
    @ApiResponse(responseCode = "200", description = "Registration accepted. The activation link is sent by email.")
    @ApiResponse(responseCode = "403", description = "`type`: `email-not-whitelisted`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`.")
    @PostMapping("/professor")
    public void registerProfessor(@Valid @RequestBody StaffRegistrationRequest r) {
        registration.registerProfessor(r.firstNames(), r.lastNames(), r.email(), r.password(), r.termsVersion());
    }

    @Operation(summary = "Manager self-registration",
               description = """
                        The email must be on the whitelist as GESTOR, managed by an ADMIN or
                        GESTOR. Like a professor, a manager does not join by invitation: someone
                        must authorize the email first.

                        `termsVersion` must be the current version returned by `/legal/terms`.""")
    @ApiResponse(responseCode = "200", description = "Registration accepted. The activation link is sent by email.")
    @ApiResponse(responseCode = "403", description = "`type`: `email-not-whitelisted`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`.")
    @PostMapping("/gestor")
    public void registerManager(@Valid @RequestBody StaffRegistrationRequest r) {
        registration.registerManager(r.firstNames(), r.lastNames(), r.email(), r.password(), r.termsVersion());
    }

    /**
     * RF-USR-04 - step 2: proof of email ownership.
     *
     * This is deliberately a POST rather than a GET, and the email link points
     * to a frontend screen that calls this endpoint. Institutional email scanners
     * would consume a GET /activate?token= before the person arrived.
     */
    @Operation(summary = "Activates the account with the link token",
               description = """
                        **This is deliberately POST rather than GET.** The email link points to a
                        frontend screen, which calls this endpoint. Institutional email scanners
                        consume a `GET /activate?token=...` before the person can click it, leaving
                        the account activated, or the token consumed, before anyone opens it.

                        The token is NOT accompanied by the email: it already identifies the account,
                        and requiring both would create an enumeration channel.""")
    @ApiResponse(responseCode = "200", description = "Account activated.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `invalid-link` (or `validation` if the token has an invalid format).
            The same error is returned for an expired, already used, or nonexistent link.""")
    @PostMapping("/activate")
    public void activate(@Valid @RequestBody ActivateAccountRequest r) {
        registration.activate(r.token());
    }

    @Operation(summary = "Resends the activation link",
               description = """
                        **Returns the SAME response whether or not the account exists**
                        (non-negotiable 5). Different responses would become an oracle for finding
                        registered addresses.

                        It sends email without authentication, so the gateway applies a stricter
                        per-IP limit than it does to the rest of `/auth`.""")
    @ApiResponse(responseCode = "200", description = "Constant response. Does not reveal whether the email exists.")
    @PostMapping("/resend-activation")
    public Map<String, String> resendActivation(@Valid @RequestBody ResendCodeRequest r) {
        return Map.of("message", registration.resendActivation(r.email()));
    }
}
