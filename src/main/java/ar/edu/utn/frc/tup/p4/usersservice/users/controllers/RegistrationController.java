package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Registro",
     description = "Alta de cuentas y verificacion de email. Anonimo: no lleva token.")
@RestController
@RequestMapping("${app.api.public-path}/registration")
public class RegistrationController {

    private final RegistrationService registro;

    public RegistrationController(RegistrationService registro) { this.registro = registro; }

    @Operation(summary = "Auto-registro de un alumno",
               description = """
                       Necesita un `invitationCode` valido: un alumno no se da de alta solo.

                       La cuenta queda en `PENDING_EMAIL` y no sirve hasta pasar por
                       `/activate` con el token del enlace que llega por mail.""")
    @ApiResponse(responseCode = "200", description = "Alta aceptada. El enlace de activacion viaja por email.")
    @ApiResponse(responseCode = "403", description = """
            `type`: `email-not-whitelisted`. El email no esta habilitado para registrarse.""")
    @ApiResponse(responseCode = "409", description = """
            `type`: `duplicate-email`. Ya existe una cuenta ACTIVA con ese email. La
            unicidad mira filas activas, no el historico: nada se borra de verdad
            (no-negociable 6), asi que un email de una cuenta dada de baja se puede reusar.""")
    @PostMapping("/student")
    public void alumno(@Valid @RequestBody StudentRegistrationRequest r) {
        registro.registrarAlumno(r.firstNames(), r.lastNames(), r.legajo(), r.email(),
                r.password(), r.invitationCode(), r.termsVersion());
    }

    @Operation(summary = "Auto-registro de un profesor",
               description = """
                       El email tiene que estar en la whitelist, que administra un ADMIN. Un
                       profesor no entra por invitacion como el alumno: entra porque alguien
                       lo habilito antes.

                       `termsVersion` tiene que ser la version vigente que devuelve
                       `/legal/terms`.""")
    @ApiResponse(responseCode = "200", description = "Alta aceptada. El enlace de activacion viaja por email.")
    @ApiResponse(responseCode = "403", description = "`type`: `email-not-whitelisted`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`.")
    @PostMapping("/professor")
    public void profesor(@Valid @RequestBody ProfessorRegistrationRequest r) {
        registro.registrarProfesor(r.firstNames(), r.lastNames(), r.email(), r.password(), r.termsVersion());
    }

    /**
     * RF-USR-04 · paso 2: verificacion de posesion del email.
     *
     * Es un POST y no un GET a proposito, y el enlace del mail apunta a una
     * pantalla del frontend que llama esto. Un GET /activate?token= lo
     * consumirian los escaneres de correo institucional antes de que la
     * persona llegara.
     */
    @Operation(summary = "Activa la cuenta con el token del enlace",
               description = """
                       **Es POST y no GET a proposito.** El enlace del mail apunta a una pantalla
                       del frontend, que es la que llama a esto. Un `GET /activate?token=...` se
                       lo consumen los escaneres de correo institucional antes de que la persona
                       llegue a hacer clic, y la cuenta queda activada -o el token quemado- sin
                       que nadie haya abierto nada.

                       El token NO viene acompañado del email: ya identifica la cuenta, y pedir
                       las dos cosas abriria un canal de enumeracion.""")
    @ApiResponse(responseCode = "200", description = "Cuenta activada.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `invalid-link` (o `validation` si el token no tiene la forma esperada).
            El mismo error para un enlace vencido, uno ya usado y uno inexistente.""")
    @PostMapping("/activate")
    public void activate(@Valid @RequestBody ActivateAccountRequest r) {
        registro.activate(r.token());
    }

    @Operation(summary = "Reenvia el enlace de activacion",
               description = """
                       **Contesta lo MISMO exista o no la cuenta** (no-negociable 5). Difieren
                       las respuestas y esto se vuelve un oraculo para averiguar que direcciones
                       estan registradas.

                       Manda mail sin autenticacion, asi que el gateway le pone un tope por IP
                       mas ajustado que al resto de `/auth`.""")
    @ApiResponse(responseCode = "200", description = "Respuesta constante. No dice si el email existe.")
    @PostMapping("/resend-activation")
    public Map<String, String> reenviar(@Valid @RequestBody ResendCodeRequest r) {
        return Map.of("message", registro.reenviarActivacion(r.email()));
    }
}
