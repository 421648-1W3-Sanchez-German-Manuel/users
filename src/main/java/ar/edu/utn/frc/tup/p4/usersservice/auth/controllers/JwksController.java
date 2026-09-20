package ar.edu.utn.frc.tup.p4.usersservice.auth.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Publishes public verification keys at the standard RFC 8615 location. */
@Tag(name = "JWKS", description = "Public signature verification keys.")
@RestController
public class JwksController {

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @Operation(summary = "Public keys for verifying token signatures",
                description = """
                        The API gateway obtains the keys here to validate each JWT signature.
                        Anonymous by definition: publishing a PUBLIC key is the purpose.

                        **It does not use the `/api` prefix**, by design: `/.well-known/` is a
                        web convention (RFC 8615), not a project convention. The gateway
                        therefore has a static route for this path, the only one besides the
                        fallback. Its locator generates only `/api/{service}/**`; without that
                        route, the gateway itself would return 404 for JWKS.

                        The private key NEVER leaves this service: it is mounted as a secret,
                        and the service refuses to start if it is missing.""")
    @ApiResponse(responseCode = "200", description = "JWKS in RFC 7517 format.")
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.publicJwks().toJSONObject();
    }
}
