package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.ScopeCatalog;
import ar.edu.utn.frc.tup.p4.usersservice.auth.repositories.ServiceClientRepository;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Authenticates service clients and validates token scope and audience. */
@Service
public class ServiceClientService {

    private final ServiceClientRepository repository;
    private final PasswordEncoder encoder;
    private final TokenService tokens;

    public ServiceClientService(
            ServiceClientRepository repository,
            PasswordEncoder encoder,
            TokenService tokens) {
        this.repository = repository;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public String emitirServicio(String clientId, String secret, String scope, String audience) {
        var client = repository.findByClientIdAndDeletedAtIsNull(clientId)
                .filter(candidate -> encoder.matches(secret, candidate.getSecretHash()))
                .orElseThrow(ApiException::invalidCredentials);

        Set<String> requestedScopes = Arrays.stream(scope == null ? new String[0] : scope.split("[ ,]+"))
                .filter(candidate -> !candidate.isBlank())
                .collect(Collectors.toSet());
        if (requestedScopes.isEmpty()) {
            throw ApiException.validation("Missing 'scope'.");
        }

        if (audience == null || audience.isBlank()) {
            throw ApiException.validation(
                    "Missing 'audience'. It must identify the destination service; there is no default.");
        }

        for (String requestedScope : requestedScopes) {
            if (!client.getAllowedScopes().contains(requestedScope)) {
                throw ApiException.validation(
                        "Scope '" + requestedScope + "' is not allowed for this client.");
            }
            if (!ScopeCatalog.esEmitible(requestedScope)) {
                throw ApiException.validation(
                        "Scope '" + requestedScope + "' cannot be issued with client_credentials. Issuable scopes: "
                                + ScopeCatalog.emitibles());
            }
        }

        Set<String> destinations = requestedScopes.stream()
                .map(ScopeCatalog::audienceDe)
                .collect(Collectors.toSet());
        if (destinations.size() > 1) {
            throw ApiException.validation(
                    "Requested scopes target more than one service " + destinations
                            + ". Request one token for each audience.");
        }

        String derivedAudience = destinations.iterator().next();
        if (!derivedAudience.equals(audience)) {
            throw ApiException.validation(
                    "Declared audience '" + audience + "' does not match scope destination '"
                            + derivedAudience + "'.");
        }

        return tokens.firmarServicio(
                TokenClaims.paraServicio(clientId, audience, requestedScopes).build());
    }
}
