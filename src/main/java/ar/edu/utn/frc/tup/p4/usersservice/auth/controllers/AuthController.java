package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.AuthService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Auth (publico)",
     description = "Login en dos fases, refresh y recupero de contraseña. Anonimo: no lleva token.")
@RestController
@RequestMapping("${app.api.public-path}/auth")
public class AuthController {

    private final AuthService auth;
    private final PasswordService passwordService;

    public AuthController(AuthService auth, PasswordService passwordService) {
        this.auth = auth;
        this.passwordService = passwordService;
    }

    @Operation(summary = "Fase 1 del login: valida credenciales y dispara el 2FA",
               description = """
                       **NO devuelve tokens.** Devuelve un `challengeId` y manda un code de 6
                       digitos por email; los tokens salen recien de `/2fa/verify`.

                       No hay servidor de mail en el stack: el code se encola en `outbox_events`
                       y se lee desde el buzon de desarrollo (`/dev/`).""")
    @ApiResponse(responseCode = "200", description = "Credenciales OK. El code viaja por email.")
    @ApiResponse(responseCode = "401", description = "`type`: `invalid-credentials`.")
    @ApiResponse(responseCode = "429", description = """
            `type`: `too-many-attempts`. Dos presupuestos distintos y separados: fallos de
            login por email, y desafios de 2FA EMITIDOS por email. El segundo existe para
            que una contraseña robada no sirva para inundar la casilla del dueño.""")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password());
    }

    @Operation(summary = "Fase 2 del login: verifica el code y emite los tokens",
               description = "Unico endpoint que emite el par access + refresh de una persona.")
    @ApiResponse(responseCode = "200", description = "Tokens emitidos.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `invalid-code` (o `validation` si el body no cumple el schema). El mismo
            error para un code incorrecto, uno vencido y un email desconocido: si difirieran
            seria un oraculo (no-negociable 5). El desafio y el code viven lo mismo, asi que
            un desafio vencido tambien sale por aca.""")
    @ApiResponse(responseCode = "429", description = "`type`: `too-many-attempts`.")
    @PostMapping("/2fa/verify")
    public TokenResponse verificar(@Valid @RequestBody VerifyTwoFactorRequest req) {
        return auth.verificarDosFa(req.challengeId(), req.code());
    }

    /** Ruta PUBLICA: el refresh va en el body, sin Authorization. */
    @Operation(summary = "Rota el refresh token y emite un access nuevo",
               description = """
                       Es PUBLICA a proposito: el refresh viaja en el body, no en `Authorization`.
                       El access ya vencido no serviria para autenticar el pedido.

                       El refresh se ROTA en cada uso. Reusar uno viejo se toma como señal de robo
                       y mata la familia entera de tokens.""")
    @ApiResponse(responseCode = "200", description = "Par nuevo. El refresh anterior queda muerto.")
    @ApiResponse(responseCode = "401", description = """
            Dos `type` distintos, y la diferencia importa porque son dos pantallas distintas:
            `session-closed` (la sesion dejo de existir: logout, cambio de contraseña o reuso
            detectado) y `session-superseded` (hubo un login mas nuevo en otro dispositivo).
            Nunca `invalid-credentials`: nadie tipeo mal una contraseña en este flujo.""")
    @PostMapping("/refresh")
    public TokenResponse refrescar(@Valid @RequestBody RefreshRequest req) {
        return auth.refrescar(req.refreshToken());
    }

    @Operation(summary = "Pide el enlace de reseteo de contraseña",
               description = """
                       **Contesta lo MISMO exista o no la cuenta** (no-negociable 5). Si las dos
                       respuestas difirieran, esto seria un oraculo para averiguar que direcciones
                       estan registradas.""")
    @ApiResponse(responseCode = "200", description = "Respuesta constante. No dice si el email existe.")
    @ApiResponse(responseCode = "429", description = "`type`: `too-many-attempts`. Tope de pedidos por email.")
    @PostMapping("/password/reset")
    public Map<String, String> pedirReset(@Valid @RequestBody ResetRequest req) {
        return Map.of("message", passwordService.pedirReset(req.email()));
    }

    /** DEC-16: its OWN path. flujos §06 drew both halves as the same POST,
     *  which is not implementable: a single @Valid cannot validate two DTOs. */
    @Operation(summary = "Confirma el reseteo con el token del enlace",
               description = """
                       Path propio y no el mismo POST que `/password/reset` (DEC-16): un solo
                       `@Valid` no puede validar dos DTOs, y OpenAPI no puede describir dos
                       schemas en una misma operacion.""")
    @ApiResponse(responseCode = "200", description = "Contraseña cambiada. La sesion queda cerrada.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `invalid-link` (o `validation` si el body no cumple el schema). El mismo
            error para un enlace vencido, uno ya usado y uno que nunca existio. Separado de
            `invalid-code` porque la salida es otra: un code se vuelve a tipear, un enlace
            hay que volver a pedirlo.""")
    @PostMapping("/password/reset/confirm")
    public void confirmarReset(@Valid @RequestBody ResetConfirmRequest req) {
        passwordService.confirmarReset(req.token(), req.newPassword());
    }
}
