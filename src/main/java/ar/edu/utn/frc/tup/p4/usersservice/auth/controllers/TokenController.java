package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.ClientCredentialsRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.ServiceClientService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Tokens de servicio",
     description = "Emision de tokens para OTROS microservicios, no para personas.")
@RestController
@RequestMapping("${app.api.public-path}/auth")
public class TokenController {

    private final ServiceClientService clients;

    public TokenController(ServiceClientService clients) {
        this.clients = clients;
    }

    @Operation(summary = "Emite un token de servicio (client_credentials)",
               description = """
                       Para comunicacion MAQUINA a MAQUINA entre microservicios de la
                       plataforma. Una persona nunca pasa por aca: su camino es
                       `/auth/login` + `/auth/2fa/verify`.

                       El token sale con un `scope` y un `audience`, y el gateway lo traduce
                       a los headers `X-Service-Id` / `X-Service-Scopes` para el destino.
                       Vida corta a proposito (`users.jwt.service-ttl`, 5 min por defecto).

                       `grantType` solo acepta `client_credentials`. Cualquier otro valor es
                       un 400, no un 501: no es una funcionalidad que falte, es un pedido
                       que este endpoint no representa.""")
    @ApiResponse(responseCode = "200", description = """
            Token emitido. El cuerpo trae `accessToken`, `tokenType` (`Bearer`) y
            `expiresIn` en segundos.""")
    @ApiResponse(responseCode = "400", description = """
            `type`: `validation`. `grantType` no soportado, `scope` vacio, o un `scope` o
            `audience` que el cliente no tiene habilitado.""")
    @ApiResponse(responseCode = "401", description = """
            `type`: `invalid-credentials`. `clientId` o `clientSecret` incorrectos. Misma
            respuesta para un cliente inexistente que para un secret equivocado.""")
    @PostMapping("/token")
    public Map<String, Object> token(@Valid @RequestBody ClientCredentialsRequest request) {
        if (!"client_credentials".equals(request.grantType())) {
            throw ApiException.validation("Unsupported grantType: " + request.grantType());
        }

        String jwt = clients.emitirServicio(
                request.clientId(),
                request.clientSecret(),
                request.scope(),
                request.audience());
        return Map.of(
                "accessToken", jwt,
                "tokenType", "Bearer",
                "expiresIn", 300);
    }
}
