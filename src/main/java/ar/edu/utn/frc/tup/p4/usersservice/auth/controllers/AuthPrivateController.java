package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.RefreshRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/auth")
public class AuthPrivateController {

    private final AuthService auth;

    public AuthPrivateController(AuthService auth) { this.auth = auth; }

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
}