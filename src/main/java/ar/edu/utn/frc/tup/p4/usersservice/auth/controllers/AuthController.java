package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.SessionCookieService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

@Tag(name = "Auth (public)",
     description = "Two-phase login, refresh, and password recovery. Anonymous: no token required.")
@RestController
@RequestMapping("${app.api.public-path}/auth")
public class AuthController {

    private final AuthService auth;
    private final PasswordService passwordService;
    private final SessionCookieService cookies;

    public AuthController(AuthService auth, PasswordService passwordService, SessionCookieService cookies) {
        this.auth = auth;
        this.passwordService = passwordService;
        this.cookies = cookies;
    }

    @Operation(summary = "Login phase 1: validates credentials and triggers 2FA",
                description = """
                        **Does NOT return tokens.** Returns a `challengeId` and sends a six-digit
                        code by email; tokens are issued only by `/2fa/verify`.

                        The stack has no mail server: the code is queued in `outbox_events` and
                        can be read from the development inbox (`/dev/`).""")
    @ApiResponse(responseCode = "200", description = "Credentials accepted. The code is sent by email.")
    @ApiResponse(responseCode = "401", description = "`type`: `invalid-credentials`.")
    @ApiResponse(responseCode = "429", description = """
             `type`: `too-many-attempts`. Two distinct, separate budgets apply: login failures
             per email and ISSUED 2FA challenges per email. The second prevents a stolen
             password from being used to flood the account owner's inbox.""")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @Operation(summary = "Login phase 2: verifies the code and issues tokens",
               description = "The only endpoint that issues a person's access and refresh token pair.")
    @ApiResponse(responseCode = "200", description = "Tokens issued.")
    @ApiResponse(responseCode = "400", description = """
             `type`: `invalid-code` (or `validation` if the body does not match the schema).
             The same error is returned for an incorrect code, an expired code, and an unknown
             email; different errors would create an enumeration oracle (non-negotiable 5).
             The challenge and code have the same lifetime, so an expired challenge also
             produces this response.""")
    @ApiResponse(responseCode = "429", description = "`type`: `too-many-attempts`.")
    @PostMapping("/2fa/verify")
    public SessionResponse verify(@Valid @RequestBody VerifyTwoFactorRequest req, HttpServletResponse response) {
        TokenResponse tokens = auth.verifyTwoFactor(req.challengeId(), req.code());
        setSessionCookies(response, tokens);
        return SessionResponse.from(tokens);
    }

    /**
     * PUBLIC route: the refresh token travels in the fu_rt cookie, never in the body.
     * Without the cookie, there is no way to distinguish "there was never a session"
     * from "the session was closed"; both use the same type as an invalid jti.
     */
    @Operation(summary = "Rotates the refresh token and issues a new access token",
                description = """
                        It is deliberately PUBLIC: the refresh token travels in the `fu_rt`
                        cookie, not in the body or `Authorization`. The expired access token
                        cannot authenticate the request.

                        The refresh token is ROTATED on every use. Reusing an old one is treated
                        as a theft signal and revokes the entire token family.""")
    @ApiResponse(responseCode = "200", description = "New pair issued. The previous refresh token is invalidated.")
    @ApiResponse(responseCode = "401", description = """
             Two distinct `type` values correspond to different client screens: `session-closed`
             (the session no longer exists because of logout, password change, detected reuse,
             or a missing cookie) and `session-superseded` (a newer login occurred on another
             device). Never `invalid-credentials`: no password is entered in this flow.""")
    @PostMapping("/refresh")
    public SessionResponse refresh(
            @CookieValue(name = SessionCookieService.REFRESH_COOKIE, required = false) String refreshJti,
            HttpServletResponse response) {
        if (refreshJti == null || refreshJti.isBlank()) {
            throw ApiException.sessionClosed();
        }
        if (!JTI_PATTERN.matcher(refreshJti).matches()) {
            throw ApiException.validation("refreshToken must be a UUID");
        }
        TokenResponse tokens = auth.refresh(refreshJti);
        setSessionCookies(response, tokens);
        return SessionResponse.from(tokens);
    }

    /**
     * The jti is a UUID. Without this check, a malformed fu_rt cookie does not
     * produce 400; it reaches Redis and uses a key that never matches, making it
     * indistinguishable from a closed session. Before moving the refresh token to
     * the cookie, RefreshRequest's @Pattern validated it through @Valid.
     */
    private static final Pattern JTI_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private void setSessionCookies(HttpServletResponse response, TokenResponse tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.access(tokens.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.refresh(tokens.refreshToken()).toString());
    }

    @Operation(summary = "Requests a password reset link",
                description = """
                        **Returns the SAME response whether or not the account exists**
                        (non-negotiable 5). Different responses would create an oracle for
                        discovering which addresses are registered.""")
    @ApiResponse(responseCode = "200", description = "Constant response. Does not reveal whether the email exists.")
    @ApiResponse(responseCode = "429", description = "`type`: `too-many-attempts`. Request limit per email.")
    @PostMapping("/password/reset")
    public Map<String, String> requestReset(@Valid @RequestBody ResetRequest req) {
        return Map.of("message", passwordService.requestReset(req.email()));
    }

    /** DEC-16: its OWN path. flujos §06 drew both halves as the same POST,
     *  which is not implementable: a single @Valid cannot validate two DTOs. */
    @Operation(summary = "Confirms the reset using the link token",
                description = """
                        Uses its own path rather than the same POST as `/password/reset` (DEC-16):
                        a single `@Valid` cannot validate two DTOs, and OpenAPI cannot describe
                        two schemas for one operation.""")
    @ApiResponse(responseCode = "200", description = "Password changed. The session is closed.")
    @ApiResponse(responseCode = "400", description = """
             `type`: `invalid-link` (or `validation` if the body does not match the schema).
             The same error is returned for an expired, previously used, or nonexistent link.
             It is separate from `invalid-code` because the recovery action differs: a code
             can be entered again, while a link must be requested again.""")
    @PostMapping("/password/reset/confirm")
    public void confirmReset(@Valid @RequestBody ResetConfirmRequest req) {
        passwordService.confirmReset(req.token(), req.newPassword());
    }
}
