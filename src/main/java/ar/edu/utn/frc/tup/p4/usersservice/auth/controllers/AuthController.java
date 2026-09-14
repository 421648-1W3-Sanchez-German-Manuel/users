package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.SessionCookieService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @PostMapping("/2fa/verify")
    public SessionResponse verificar(@Valid @RequestBody VerifyTwoFactorRequest req, HttpServletResponse response) {
        TokenResponse tokens = auth.verificarDosFa(req.challengeId(), req.code());
        setSessionCookies(response, tokens);
        return SessionResponse.from(tokens);
    }

    /**
     * Ruta PUBLICA: el refresh viaja en la cookie fu_rt, nunca en el body.
     * Sin la cookie no hay forma de distinguir "nunca hubo sesion" de
     * "sesion cerrada" - se trata igual, mismo type que un jti invalido.
     */
    @PostMapping("/refresh")
    public SessionResponse refrescar(
            @CookieValue(name = SessionCookieService.REFRESH_COOKIE, required = false) String refreshJti,
            HttpServletResponse response) {
        if (refreshJti == null || refreshJti.isBlank()) {
            throw ApiException.sessionClosed();
        }
        TokenResponse tokens = auth.refrescar(refreshJti);
        setSessionCookies(response, tokens);
        return SessionResponse.from(tokens);
    }

    private void setSessionCookies(HttpServletResponse response, TokenResponse tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.access(tokens.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.refresh(tokens.refreshToken()).toString());
    }

    @PostMapping("/password/reset")
    public Map<String, String> pedirReset(@Valid @RequestBody ResetRequest req) {
        return Map.of("message", passwordService.pedirReset(req.email()));
    }

    /** DEC-16: its OWN path. flujos §06 drew both halves as the same POST,
     *  which is not implementable: a single @Valid cannot validate two DTOs. */
    @PostMapping("/password/reset/confirm")
    public void confirmarReset(@Valid @RequestBody ResetConfirmRequest req) {
        passwordService.confirmarReset(req.token(), req.newPassword());
    }
}