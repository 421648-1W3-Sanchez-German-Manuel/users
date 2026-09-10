package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/public/auth")
public class AuthController {

    private final AuthService auth;
    private final PasswordService passwordService;

    public AuthController(AuthService auth, PasswordService passwordService) {
        this.auth = auth;
        this.passwordService = passwordService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @PostMapping("/2fa/verify")
    public TokenResponse verificar(@Valid @RequestBody VerifyTwoFactorRequest req) {
        return auth.verificarDosFa(req.challengeId(), req.code());
    }

    /** Ruta PUBLICA: el refresh va en el body, sin Authorization. */
    @PostMapping("/refresh")
    public TokenResponse refrescar(@Valid @RequestBody RefreshRequest req) {
        return auth.refrescar(req.refreshToken());
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