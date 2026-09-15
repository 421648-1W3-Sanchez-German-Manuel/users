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

@Tag(name = "Auth (sesion)",
     description = "Operaciones sobre la sesion propia. Exigen un access token vigente.")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
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
     * El refresh a revocar viaja en la cookie fu_rt, no en el body: con
     * HttpOnly el front ya no la puede leer para mandarla el mismo.
     */
    @Operation(summary = "Cierra la sesion",
               description = """
                       Exento de los TRES gates: alguien con la cuenta pendiente, con cambio de
                       contraseña forzado o con el onboarding sin terminar tiene que poder salir
                       igual. Una cuenta frenada que ademas no puede desloguearse es una trampa.

                       El body es OPCIONAL: sin el se cierra la sesion, y con el `refreshToken`
                       se mata ademas esa familia de tokens.

                       El gateway cachea el estado de sesion 3 s, asi que durante esa ventana el
                       token viejo puede seguir entrando. Esperar ~4 s antes de concluir que el
                       logout no anduvo.""")
    @ApiResponse(responseCode = "200", description = "Sesion cerrada.")
    @PostMapping("/logout")
    @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    public void logout(@AuthenticationPrincipal GatewayPrincipal p,
                       @CookieValue(name = SessionCookieService.REFRESH_COOKIE, required = false) String refreshJti,
                       HttpServletResponse response) {
        exigirPersona(p);
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
    @Operation(summary = "Cambia la contraseña propia",
               description = """
                       Es la SALIDA del gate de contraseña, asi que esta exenta de ese gate y
                       tambien del de onboarding. Sin la segunda exencion el ADMIN inicial queda
                       encerrado: nace con las dos condiciones pendientes a la vez, asi que esta
                       ruta la cortaria el gate de onboarding y `/me/onboarding` la cortaria el
                       de contraseña.

                       **Cambiar la contraseña CIERRA la sesion.** Hay que volver a loguearse;
                       los tokens viejos dejan de servir.""")
    @ApiResponse(responseCode = "200", description = "Contraseña cambiada. La sesion queda cerrada.")
    @ApiResponse(responseCode = "401", description = """
            `type`: `invalid-credentials` si `currentPassword` no coincide, o
            `not-authenticated` si el request no trae identidad.""")
    @PostMapping("/password/change")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    public void cambiar(@AuthenticationPrincipal GatewayPrincipal p,
                        @Valid @RequestBody PasswordChangeRequest req) {
        exigirPersona(p);
        passwordService.cambiar(p.id(), req.currentPassword(), req.newPassword());
    }

    /**
     * Estas dos rutas operan sobre la sesion de una PERSONA. Un principal de
     * tipo "service" llega con id() == null (GatewayIdentityFilter), y el
     * AccountGateInterceptor lo deja pasar porque los gates son de cuentas.
     * Sin este chequeo, un token de servicio entra igual y termina operando
     * sobre la clave session:null.
     *
     * 403 y no 401: no falta identidad, sobra. Un 401 mandaria al login (regla 3).
     */
    private void exigirPersona(GatewayPrincipal p) {
        if (p == null || !p.isPerson() || p.id() == null) throw ApiException.accessDenied();
    }
}
