package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/public/registration")
public class RegistrationController {

    private final RegistrationService registro;

    public RegistrationController(RegistrationService registro) { this.registro = registro; }

    @PostMapping("/student")
    public void alumno(@Valid @RequestBody StudentRegistrationRequest r) {
        registro.registrarAlumno(r.firstNames(), r.lastNames(), r.legajo(), r.email(),
                r.password(), r.invitationCode(), r.termsVersion());
    }

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
    @PostMapping("/activate")
    public void activate(@Valid @RequestBody ActivateAccountRequest r) {
        registro.activate(r.token());
    }

    @PostMapping("/resend-activation")
    public Map<String, String> reenviar(@Valid @RequestBody ResendCodeRequest r) {
        return Map.of("message", registro.reenviarActivacion(r.email()));
    }
}
