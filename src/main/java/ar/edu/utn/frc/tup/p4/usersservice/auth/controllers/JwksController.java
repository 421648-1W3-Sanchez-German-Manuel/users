package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Publishes public verification keys at the standard RFC 8615 location. */
@Tag(name = "JWKS", description = "Claves publicas de verificacion de firma.")
@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @Operation(summary = "Claves publicas para verificar la firma de los tokens",
               description = """
                       Es de donde el API gateway saca las claves para validar la firma de
                       cada JWT. Anonimo por definicion: publicar una clave PUBLICA es el
                       punto.

                       **No lleva el prefijo `/api`**, y no es un olvido: `/.well-known/` es
                       una convencion web (RFC 8615), no del proyecto. Por eso el gateway
                       tiene una ruta estatica para este path -la unica ademas del fallback-:
                       su locator solo genera `/api/{servicio}/**` y sin esa ruta el JWKS
                       moriria en un 404 del propio gateway.

                       La privada NUNCA sale de aca: se monta como secreto y el servicio se
                       niega a arrancar si falta.""")
    @ApiResponse(responseCode = "200", description = "JWKS en el formato de RFC 7517.")
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.jwksPublico().toJSONObject();
    }
}
