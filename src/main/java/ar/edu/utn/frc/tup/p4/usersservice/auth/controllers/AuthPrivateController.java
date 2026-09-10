package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.PasswordChangeRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.RefreshRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/auth")
public class AuthPrivateController {

    private final AuthService auth;
    private final PasswordService passwordService;

    public AuthPrivateController(AuthService auth, PasswordService passwordService) {
        this.auth = auth;
        this.passwordService = passwordService;
    }

    /**
     * Exempt from ALL three gates: someone with a pending account, a forced
     * password change or pending onboarding still has to be able to log out.
     * The exit endpoint of a gate is exempt from both fine-grained gates.
     */
    @PostMapping("/logout")
    @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    public void logout(@AuthenticationPrincipal GatewayPrincipal p,
                       @RequestBody(required = false) RefreshRequest req) {
        auth.logout(p.id(), req == null ? null : req.refreshToken());
    }

    /**
     * Exempt from PASSWORD (it is that gate's way out) and from ONBOARDING too.
     * Without the second, RF-USR-01's initial ADMIN is locked out: it is born
     * with mustChangePassword=true AND firstLogin=true, so this route would be
     * cut by the onboarding gate while /me/onboarding would be cut by the
     * password one. See the exemption rule in task 8.
     */
    @PostMapping("/password/change")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    public void cambiar(@AuthenticationPrincipal GatewayPrincipal p,
                        @Valid @RequestBody PasswordChangeRequest req) {
        passwordService.cambiar(p.id(), req.currentPassword(), req.newPassword());
    }
}