package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.RateLimitProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final CredentialService credenciales;     // la puerta a users/
    private final SecondFactorProvider segundoFactor;
    private final TokenService tokens;
    private final TokenStore store;
    private final EphemeralTokenService efimeros;
    private final JwtProperties jwt;
    private final RateLimitProperties rate;

    public AuthService(CredentialService credenciales, SecondFactorProvider segundoFactor,
                       TokenService tokens, TokenStore store, EphemeralTokenService efimeros,
                       JwtProperties jwt, RateLimitProperties rate) {
        this.credenciales = credenciales; this.segundoFactor = segundoFactor;
        this.tokens = tokens; this.store = store; this.efimeros = efimeros;
        this.jwt = jwt; this.rate = rate;
    }

    /** Fase 1: valida credenciales y dispara el 2FA. NO emite tokens. */
    @Transactional
    public LoginResponse login(String email, String password) {
        String key = email.toLowerCase(Locale.ROOT);

        // DEC-42: the limit is checked BEFORE spending a BCrypt (~100 ms).
        if (store.incrementarFallos(key, rate.loginVentana()) > rate.loginMaxFallos()) {
            throw ApiException.tooManyAttempts(rate.loginVentana());
        }

        var verificadas = credenciales.verifyCredentials(key, password);
        if (verificadas == null) throw ApiException.invalidCredentials();

        store.limpiarFallos(key);   // acerto: no consume presupuesto

        String challengeId = UUID.randomUUID().toString();
        efimeros.guardar("desafio:" + challengeId, verificadas.userId().toString(), Duration.ofMinutes(5));
        segundoFactor.generarDesafio(verificadas.userId(), verificadas.email(), verificadas.firstNames());

        return new LoginResponse(challengeId, "Te enviamos un code por email.");
    }

    /** Fase 2: verifica el code y recien ahi emite los tokens. */
    @Transactional
    public TokenResponse verificarDosFa(String challengeId, String code) {
        UUID userId = efimeros.verificar("desafio:" + challengeId)
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidCode);

        segundoFactor.verificar(userId, code);
        efimeros.consumir("desafio:" + challengeId);

        return emitirParDeTokens(userId);
    }

    /**
     * DEC-22 - the post-2FA login is the ONLY operation that writes
     * session:{userId}. El refresh no la toca.
     */
    @Transactional
    public TokenResponse emitirParDeTokens(UUID userId) {
        String sid = UUID.randomUUID().toString();
        store.guardarSesion(userId, sid);
        return emitirConSid(userId, sid, UUID.randomUUID().toString());
    }

    TokenResponse emitirConSid(UUID userId, String sid, String familyId) {
        var datos = credenciales.tokenData(userId);   // ver Tarea 14, Step 3

        TokenClaims claims = TokenClaims.paraPersona(userId, datos.roles(), sid,
                datos.accountStatus(), datos.mustChangePassword(), datos.firstLogin()).build();

        String refreshJti = UUID.randomUUID().toString();
        store.guardarRefresh(refreshJti,
                new TokenStore.RefreshData(userId, sid, familyId), jwt.refreshTtl());

        return new TokenResponse(tokens.firmarPersona(claims), refreshJti, jwt.accessTtl().toSeconds());
    }
}