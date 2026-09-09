package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.ClientCredentialsRequest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.ServiceClientService;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users/public/auth")
public class TokenController {

    private final ServiceClientService clients;

    public TokenController(ServiceClientService clients) {
        this.clients = clients;
    }

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
