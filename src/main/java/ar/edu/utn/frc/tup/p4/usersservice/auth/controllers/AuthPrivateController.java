package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.PasswordChangeRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.SessionCookieService;
import ar.edu.utn.frc.tup.p4.usersservice.config.OpenApiConfig;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth (session)",
     description = "Operations on the current session. They require a valid access token.")
@SecurityRequirement(name = OpenApiConfig.COOKIE_SCHEME)
@RestController
@RequestMapping("${app.api.private-path}/auth")
public class AuthPrivateController {

    private final AuthService auth;
    private final PasswordService passwordService;
    private final SessionCookieService cookies;

    public AuthPrivateController(AuthService auth, PasswordService passwordService, SessionCookieService cookies) {
        this.auth = auth;
        this.passwordService = passwordService;
        this.cookies = cookies;
    }

    /**
     * Exempt from ALL three gates: someone with a pending account, a forced
     * password change or pending onboarding still has to be able to log out.
     * The exit endpoint of a gate is exempt from both fine-grained gates.
     *
     * The refresh token to revoke travels in the fu_rt cookie, not in the body:
     * HttpOnly prevents the frontend from reading and sending it itself.
     */
    @Operation(summary = "Closes the session",
               description = """
                        Exempt from ALL THREE gates: someone with a pending account, a forced
                        password change, or incomplete onboarding must still be able to log out.
                        Blocking an account from both progressing and logging out creates a trap.

                        The body is OPTIONAL: without it, the session is closed; with the
                        `refreshToken`, that token family is also terminated.

                        The gateway caches session state for 3 seconds, so the old token may still
                        be accepted during that window. Wait about 4 seconds before concluding
                        that logout did not work.""")
    @ApiResponse(responseCode = "200", description = "Session closed.")
    @PostMapping("/logout")
    @SkipAccountGate({SkipAccountGate.Gate.ACCOUNT_STATUS, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    public void logout(@AuthenticationPrincipal GatewayPrincipal p,
                       @CookieValue(name = SessionCookieService.REFRESH_COOKIE, required = false) String refreshJti,
                       HttpServletResponse response) {
        requirePerson(p);
        auth.logout(p.id(), refreshJti);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clearAccess().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clearRefresh().toString());
    }

    /**
     * Exempt from PASSWORD (it is that gate's way out) and from ONBOARDING too.
     * Without the second, RF-USR-01's initial ADMIN is locked out: it is born
     * with mustChangePassword=true AND firstLogin=true, so this route would be
     * cut by the onboarding gate while /me/onboarding would be cut by the
     * password one. See the exemption rule in task 8.
     */
    @Operation(summary = "Changes the current user's password",
               description = """
                        This is the EXIT from the password gate, so it is exempt from that gate and
                        the onboarding gate. Without the second exemption, the initial ADMIN would
                        be locked out: both conditions are pending at creation, so the onboarding
                        gate would block this route and the password gate would block
                        `/me/onboarding`.

                        **Changing the password CLOSES the session.** The user must sign in again;
                        the old tokens are no longer valid.""")
    @ApiResponse(responseCode = "200", description = "Password changed. The session is closed.")
    @ApiResponse(responseCode = "401", description = """
            `type`: `invalid-credentials` if `currentPassword` does not match, or
            `not-authenticated` if the request carries no identity.""")
    @PostMapping("/password/change")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    public void changePassword(@AuthenticationPrincipal GatewayPrincipal p,
                               @Valid @RequestBody PasswordChangeRequest req) {
        requirePerson(p);
        passwordService.change(p.id(), req.currentPassword(), req.newPassword());
    }

    /**
     * These two routes operate on a PERSON'S session. A principal of type
     * "service" arrives with id() == null (GatewayIdentityFilter), and the
     * AccountGateInterceptor allows it through because the gates apply to accounts.
     * Without this check, a service token would still enter and operate on the
     * session:null key.
     *
     * 403, not 401: identity is not missing; it is the wrong kind. A 401 would
     * redirect to login (rule 3).
     */
    private void requirePerson(GatewayPrincipal p) {
        if (p == null || !p.isPerson() || p.id() == null) throw ApiException.accessDenied();
    }
}
