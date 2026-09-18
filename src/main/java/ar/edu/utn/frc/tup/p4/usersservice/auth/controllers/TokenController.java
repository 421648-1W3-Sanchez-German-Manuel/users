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

@Tag(name = "Service tokens",
     description = "Issues tokens for OTHER microservices, not for people.")
@RestController
@RequestMapping("${app.api.public-path}/auth")
public class TokenController {

    private final ServiceClientService clients;

    public TokenController(ServiceClientService clients) {
        this.clients = clients;
    }

    @Operation(summary = "Issues a service token (client_credentials)",
                description = """
                        For MACHINE-TO-MACHINE communication between platform microservices.
                        A person never uses this endpoint; their flow is
                        `/auth/login` + `/auth/2fa/verify`.

                        The token includes a `scope` and an `audience`, which the gateway
                        translates into `X-Service-Id` / `X-Service-Scopes` headers for the
                        destination. It is deliberately short-lived (`users.jwt.service-ttl`,
                        five minutes by default).

                        `grantType` accepts only `client_credentials`. Any other value produces
                        400, not 501: the request is outside this endpoint's contract, rather
                        than an unimplemented feature.""")
    @ApiResponse(responseCode = "200", description = """
             Token issued. The body contains `accessToken`, `tokenType` (`Bearer`), and
             `expiresIn` in seconds.""")
    @ApiResponse(responseCode = "400", description = """
             `type`: `validation`. Unsupported `grantType`, empty `scope`, or a `scope` or
             `audience` that is not enabled for the client.""")
    @ApiResponse(responseCode = "401", description = """
             `type`: `invalid-credentials`. Incorrect `clientId` or `clientSecret`. The same
             response is returned for an unknown client and an incorrect secret.""")
    @PostMapping("/token")
    public Map<String, Object> token(@Valid @RequestBody ClientCredentialsRequest request) {
        if (!"client_credentials".equals(request.grantType())) {
            throw ApiException.validation("Unsupported grantType: " + request.grantType());
        }

        String jwt = clients.issueServiceToken(
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
