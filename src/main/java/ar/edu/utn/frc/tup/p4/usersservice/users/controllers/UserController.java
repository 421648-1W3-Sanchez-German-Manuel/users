package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    /**
     * Exenta de los tres gates. Es la ruta que le dice a la persona POR QUE
     * esta frenada: si el gate la cortara, la cuenta quedaria como una caja
     * negra y el frontend no tendria como explicar el bloqueo.
     */
    @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    @GetMapping("/me")
    public UserMeResponse me(@AuthenticationPrincipal GatewayPrincipal p) {
        return users.me(p.id());
    }

    /**
     * Es la SALIDA del gate de onboarding, asi que esta exenta de los dos
     * gates finos (regla 4). Sin la exencion de ONBOARDING la ruta queda
     * cortada por el gate que viene a resolver y la cuenta no sale nunca; sin
     * la de PASSWORD se traba el ADMIN inicial, que nace con las dos
     * condiciones pendientes a la vez.
     */
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    @PatchMapping("/me/onboarding")
    public void onboarding(@AuthenticationPrincipal GatewayPrincipal p,
                           @Valid @RequestBody OnboardingRequest r) {
        users.completeOnboarding(p.id(), r.githubUsername(), r.avatarRef(), r.tourOk());
    }

    @GetMapping("/profile/{id}")
    public ProfileResponse perfil(@PathVariable UUID id) {
        return users.perfil(id);
    }

    /**
     * Layer 1 in the annotation (does it have the role?). Layer 2 in the service
     * (does it leave the platform without an ADMIN?).
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> crear(@Valid @RequestBody CreateUserRequest r) {
        return Map.of("id", users.crear(r.firstNames(), r.lastNames(), r.email(),
                r.password(), r.role()).toString());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void baja(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                     @Valid @RequestBody AdminDeactivationRequest req) {
        users.deactivate(p.id(), id, req);
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public void changeRole(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                           @Valid @RequestBody RoleChangeRequest req) {
        users.changeRole(p.id(), id, req.role());
    }
}
