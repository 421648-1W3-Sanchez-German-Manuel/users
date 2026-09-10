package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.TokenStore;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class PasswordService {

    private static final String RESPUESTA_CONSTANTE = "Si el email existe, te enviamos las instrucciones.";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** DEC-16. Corto a proposito: un reset vigente es una cuenta tomable. */
    private static final Duration TTL_RESET = Duration.ofMinutes(15);

    private final CredentialService credenciales;
    private final EphemeralTokenService efimeros;
    private final TokenStore store;
    private final NotificationEventPublisher mails;
    private final String urlFront;

    public PasswordService(CredentialService credenciales, EphemeralTokenService efimeros,
                           TokenStore store, NotificationEventPublisher mails,
                           @Value("${users.front-url:https://app.tpi.utn.frc}") String urlFront) {
        this.credenciales = credenciales; this.efimeros = efimeros;
        this.store = store; this.mails = mails; this.urlFront = urlFront;
    }

    @Transactional
    public void cambiar(UUID userId, String actual, String nueva) {
        if (!credenciales.verifyPasswordOf(userId, actual)) throw ApiException.invalidCredentials();
        credenciales.updatePassword(userId, nueva);
        store.borrarSesion(userId);     // cerrar sesiones viejas
    }

    /**
     * DEC-16 - half 1: REQUEST. It ALWAYS returns the same, e-mail or no e-mail.
     * DEC-33: this does NOT become a 6-digit code. Guessing a reset IS taking
     * la cuenta; activate un email no le da acceso a nadie. Distinto impacto,
     * distinto mecanismo.
     */
    @Transactional
    public String pedirReset(String email) {
        var datos = credenciales.findForPasswordReset(email);
        if (datos != null) {
            // Mismo esquema que el enlace de activacion (RegistrationService):
            //   reset:{sha256(token)}   -> userId   (por donde entra el enlace)
            //   reset:email:{email}     -> sha256   (indice, para invalidar al reenviar)
            // Sin el indice, cada pedido dejaba vivo tambien el enlace anterior
            // y se acumulaban N enlaces validos a la vez.
            efimeros.consumir(claveIndice(datos.email()))
                    .ifPresent(hashViejo -> efimeros.consumir(claveReset(hashViejo)));

            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String hash = hashear(token);

            efimeros.guardar(claveReset(hash), datos.userId().toString(), TTL_RESET);
            efimeros.guardar(claveIndice(datos.email()), hash, TTL_RESET);
            mails.enviar(EmailType.RESET_PASSWORD, datos.email(), Map.of(
                    "firstNames", datos.firstNames(),
                    "enlace", urlFront + "/reset?token=" + token));
        }
        return RESPUESTA_CONSTANTE;
    }

    /** DEC-16 - half 2: CONFIRM. Its own path, its own body. */
    @Transactional
    public void confirmarReset(String token, String nueva) {
        // La politica se valida ANTES de quemar el token. consumir() borra la
        // clave de Redis, y Redis esta fuera del rollback de @Transactional:
        // con el orden anterior, escribir una password que no pasa la politica
        // devolvia 400 Y dejaba el enlace muerto. Habia que pedir otro mail
        // por haberse equivocado al tipear.
        PasswordPolicy.validate(nueva);

        UUID userId = efimeros.consumir(claveReset(hashear(token)))   // un solo uso, atomico
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidCode);

        credenciales.updatePassword(userId, nueva);
        store.borrarSesion(userId);
    }

    private String claveReset(String hash) { return "reset:" + hash; }

    private String claveIndice(String email) { return "reset:email:" + email; }

    /**
     * El token se guarda HASHEADO: quien pueda leer Redis no tiene que poder
     * tomar cuentas ajenas. Un dump, una replica, un backup o un MONITOR
     * entregaban un enlace de reset funcional por cada pedido vigente.
     *
     * SHA-256 pelado alcanza, por el mismo motivo que en RegistrationService:
     * el token ya son 256 bits aleatorios, no hay diccionario que atacar y no
     * hace falta el costo de BCrypt en el camino caliente.
     */
    private String hashear(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}