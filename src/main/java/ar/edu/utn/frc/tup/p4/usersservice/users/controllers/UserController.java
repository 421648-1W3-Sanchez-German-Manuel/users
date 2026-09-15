package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.config.OpenApiConfig;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Usuarios",
     description = "Cuenta propia, perfiles y administracion de usuarios.")
@SecurityRequirement(name = OpenApiConfig.COOKIE_SCHEME)
@RestController
@RequestMapping("${app.api.private-path}")
public class UserController {

    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    /**
     * Exenta de los tres gates. Es la ruta que le dice a la persona POR QUE
     * esta frenada: si el gate la cortara, la cuenta quedaria como una caja
     * negra y el frontend no tendria como explicar el bloqueo.
     */
    @Operation(summary = "La cuenta propia, completa",
               description = """
                       Exenta de los TRES gates, y es la unica que lo esta por este motivo: es
                       la ruta que le dice a la persona POR QUE esta frenada. Si un gate la
                       cortara, la cuenta seria una caja negra y el frontend no tendria con que
                       explicar el bloqueo.

                       Mirar `accountStatus`, `mustChangePassword` y `firstLogin` para saber a
                       que pantalla mandar antes de dejar entrar al resto de la app.""")
    @ApiResponse(responseCode = "200", description = "La cuenta propia, incluidos email y legajo.")
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
    @Operation(summary = "Completa el onboarding",
               description = """
                       Es la SALIDA del gate de onboarding, asi que esta exenta de los dos gates
                       finos. Sin la exencion de ONBOARDING la ruta la cortaria el gate que
                       viene a resolver y la cuenta no saldria nunca; sin la de PASSWORD se
                       traba el ADMIN inicial, que nace con las dos condiciones pendientes.

                       `avatarRef` es OPCIONAL.""")
    @ApiResponse(responseCode = "200", description = "Onboarding completo. El gate deja de cortar.")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    @PatchMapping("/me/onboarding")
    public void onboarding(@AuthenticationPrincipal GatewayPrincipal p,
                           @Valid @RequestBody OnboardingRequest r) {
        users.completeOnboarding(p.id(), r.githubUsername(), r.avatarRef(), r.tourOk());
    }

    @Operation(summary = "Perfil publico de otra persona",
               description = """
                       Lo que ve cualquier companero: nombre, usuario de GitHub y avatar.
                       **NO trae email, ni legajo, ni estado de cuenta**; para eso esta `/me`,
                       que devuelve la cuenta PROPIA.""")
    @ApiResponse(responseCode = "200", description = "Perfil publico.")
    @ApiResponse(responseCode = "404", description = "`type`: `route-not-found`.")
    @GetMapping("/profile/{id}")
    public ProfileResponse perfil(@PathVariable UUID id) {
        return users.perfil(id);
    }

    /** ADMIN directory. Only active accounts, newest first. */
    @Operation(summary = "Directorio de usuarios (ADMIN)",
               description = "Solo cuentas ACTIVAS, de la mas nueva a la mas vieja.")
    @ApiResponse(responseCode = "200", description = "Listado de cuentas activas.")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserListItemResponse> listar() {
        return users.listar();
    }

    /**
     * Layer 1 in the annotation (does it have the role?). Layer 2 in the service
     * (does it leave the platform without an ADMIN?).
     */
    @Operation(summary = "Crea un ADMIN (ADMIN)",
               description = """
                       **Solo crea ADMIN.** PROFESSOR y STUDENT entran unicamente por whitelist
                       + auto-registro: un ADMIN dandolos de alta con contraseña directa los
                       dejaba en `PENDING_EMAIL` sin enlace de activacion, o sea una cuenta
                       imposible de activar.

                       La cuenta nace con cambio de contraseña forzado y onboarding pendiente.""")
    @ApiResponse(responseCode = "200", description = "Creado. Devuelve el `id`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`.")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> crear(@Valid @RequestBody CreateUserRequest r) {
        return Map.of("id", users.crear(r.firstNames(), r.lastNames(), r.email(),
                r.password()).toString());
    }

    @Operation(summary = "Da de baja una cuenta (ADMIN)",
               description = """
                       Baja LOGICA: nada se borra de verdad (no-negociable 6).

                       Pide reautenticacion completa en el body -contraseña, code de 2FA y el
                       nombre de usuario escrito a mano- porque es una operacion destructiva
                       sobre la cuenta de otra persona.

                       Dos capas de defensa distintas: la anotacion pregunta si tiene el rol, y
                       el servicio pregunta si la operacion deja la plataforma sin ningun ADMIN
                       activo. Un ADMIN tampoco puede darse de baja a si mismo.""")
    @ApiResponse(responseCode = "200", description = "Cuenta dada de baja.")
    @ApiResponse(responseCode = "401", description = "`type`: `invalid-credentials`. La reautenticacion fallo.")
    @ApiResponse(responseCode = "409", description = """
            `type`: `last-admin`. La plataforma no puede quedarse sin ADMIN activo.""")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void baja(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                     @Valid @RequestBody AdminDeactivationRequest req) {
        users.deactivate(p.id(), id, req);
    }

    @Operation(summary = "Cambia el rol de una cuenta (ADMIN)",
               description = """
                       Misma defensa de dos capas que la baja: bajarle el rol al ultimo ADMIN
                       activo deja la plataforma sin nadie que administre, asi que el servicio
                       lo corta aunque quien lo pida sea ADMIN.""")
    @ApiResponse(responseCode = "200", description = "Rol cambiado.")
    @ApiResponse(responseCode = "409", description = "`type`: `last-admin`.")
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public void changeRole(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                           @Valid @RequestBody RoleChangeRequest req) {
        users.changeRole(p.id(), id, req.role());
    }
}
