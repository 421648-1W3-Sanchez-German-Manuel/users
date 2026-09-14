package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** DEC-31 - public with NO token: it has to be readable BEFORE having an account. */
@Tag(name = "Legal", description = "Terminos y condiciones vigentes.")
@RestController
@RequestMapping("${app.api.public-path}/legal")
public class LegalController {

    private final String version;

    public LegalController(@Value("${users.legal.terms-version}") String version) {
        this.version = version;
    }

    @Operation(summary = "Terminos y condiciones vigentes",
               description = """
                       Publico y SIN token a proposito: hay que poder leerlo ANTES de tener
                       cuenta. Exigir un token para leer lo que hay que aceptar para crear la
                       cuenta seria un circulo.

                       El campo `version` es el que va en `termsVersion` al registrarse.""")
    @ApiResponse(responseCode = "200",
                 description = "`version` (la vigente) y `texto` (el contenido en Markdown).")
    @GetMapping("/terms")
    public Map<String, String> tyc() throws Exception {
        String texto = new String(new ClassPathResource("legal/terms-" + version + ".md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Map.of("version", version, "texto", texto);
    }
}
