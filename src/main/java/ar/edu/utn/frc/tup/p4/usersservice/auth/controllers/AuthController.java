package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/public/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

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
}