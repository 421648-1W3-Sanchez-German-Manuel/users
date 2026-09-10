package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.auth.twofactor.SecondFactorProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.OtpProperties;
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
    private final OtpProperties otpProps;

    public AuthService(CredentialService credenciales, SecondFactorProvider segundoFactor,
                       TokenService tokens, TokenStore store, EphemeralTokenService efimeros,
                       JwtProperties jwt, RateLimitProperties rate,
                       OtpProperties otpProps) {
        this.credenciales = credenciales; this.segundoFactor = segundoFactor;
        this.tokens = tokens; this.store = store; this.efimeros = efimeros;
        this.jwt = jwt; this.rate = rate; this.otpProps = otpProps;
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
        // El desafio y el code tienen que vivir lo MISMO. Con el 5 hardcodeado,
        // subir users.otp.two-factor-ttl a PT10M rompia todo login entre el
        // minuto 5 y el 10: code vivo, desafio vencido, invalid-code. Un cambio
        // solo de configuracion, sin senal de compilacion ni de tests.
        efimeros.guardar("desafio:" + challengeId, verificadas.userId().toString(), otpProps.dosfaTtl());
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

    /**
     * DEC-22 - four steps, and step 3 is the one this spec adds over what
     * manifiesto-flujos §10 says ("checking here or letting it fail at the gateway
     * are equivalent"). They are NOT: the refresh lives 7 DAYS. Without the check,
     * un dispositivo superado conserva una credencial de larga vida, robable,
     * tied to a session that no longer exists.
     */
    @Transactional
    public TokenResponse refrescar(String refreshJti) {
        // Reuse detection: a rotated token coming back is a theft signal. The
        // whole family dies with it.
        //
        // El prefijo NO puede empezar con "refresh:": ese namespace es de
        // RedisTokenStore (REFRESH_PREFIX y REVOKED_FAMILY_PREFIX). Con
        // "refresh:rotado:", mandar refreshToken="rotado:<jti>" hacia que
        // store.refresh() leyera esta misma clave, cuyo valor es un familyId
        // pelado y no el JSON de RefreshData: 500 en vez de 401.
        var rotado = efimeros.verificar(claveRotado(refreshJti));
        if (rotado.isPresent()) {
            store.revocarFamilia(rotado.get());
            throw ApiException.sessionClosed();
        }

        // The exits of this method return a SESSION type, not invalid-credentials:
        // nobody mistyped a password, the session stopped existing. The frontend
        // branches on type, and with invalid-credentials it would show "wrong
        // username or password" in a flow where neither was requested.
        var data = store.refresh(refreshJti).orElseThrow(ApiException::sessionClosed);

        // 1-2. Familia revocada -> senal de robo previa.
        if (store.familiaRevocada(data.familyId())) {
            throw ApiException.sessionClosed();
        }

        // 3. Is the session still the current one? If not, there was a newer login:
        // that case has its own type, which is the only message useful to the
        // persona ("iniciaste sesion en otro dispositivo").
        String sidVigente = store.sidDe(data.userId()).orElse(null);
        if (sidVigente == null || !sidVigente.equals(data.sid())) {
            store.revocarFamilia(data.familyId());
            throw sidVigente == null ? ApiException.sessionClosed() : ApiException.sessionSuperseded();
        }

        // 4. Rotate the REFRESH (not the sid). Reuse detection: the old one dies,
        // and its key marks the family for the rest of the refresh life.
        store.revocarRefresh(refreshJti);
        efimeros.guardar(claveRotado(refreshJti), data.familyId(), jwt.refreshTtl());
        return emitirConSid(data.userId(), data.sid(), data.familyId());
    }

    private String claveRotado(String jti) { return "rotado:refresh:" + jti; }

    /**
     * DEC-02 + DEC-22: uno de los dos unicos borrados de session:{userId}.
     *
     * El refresh que llega en el body tiene que ser DEL QUE LLAMA. Sin ese
     * filtro, cualquiera que conozca el jti de otro le mata la familia entera
     * de tokens desde su propia sesion: un logout ajeno a pedido.
     */
    @Transactional
    public void logout(UUID userId, String refreshJti) {
        if (refreshJti != null) {
            store.refresh(refreshJti)
                    .filter(d -> d.userId().equals(userId))
                    .ifPresent(d -> {
                        store.revocarFamilia(d.familyId());
                        store.revocarRefresh(refreshJti);
                    });
        }
        store.borrarSesion(userId);
    }
}