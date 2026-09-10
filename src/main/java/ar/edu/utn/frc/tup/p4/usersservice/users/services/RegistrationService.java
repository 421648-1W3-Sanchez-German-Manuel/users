package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.config.KafkaTopicsProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.AccountEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.EmailWhitelistRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RegistrationService {

    private static final String RESPUESTA_CONSTANTE = "Si el email existe, te enviamos un enlace.";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 24 h. With a 256-bit token there is no tension between lifetime and brute force. */
    private static final Duration TTL_ACTIVACION = Duration.ofHours(24);

    private final UserRepository repo;
    private final EmailWhitelistRepository whitelist;
    private final PasswordEncoder encoder;
    private final EphemeralTokenService efimeros;
    private final NotificationEventPublisher mails;
    private final AccountEventPublisher eventos;
    private final KafkaTopicsProperties topics;
    private final String tycVigente;
    private final String urlFront;

    public RegistrationService(UserRepository repo, EmailWhitelistRepository whitelist,
                           PasswordEncoder encoder, EphemeralTokenService efimeros,
                           NotificationEventPublisher mails, AccountEventPublisher eventos,
                           KafkaTopicsProperties topics,
                           @Value("${users.legal.terms-version}") String tycVigente,
                           @Value("${users.front-url:https://app.tpi.utn.frc}") String urlFront) {
        this.repo = repo; this.whitelist = whitelist; this.encoder = encoder;
        this.efimeros = efimeros; this.mails = mails;
        this.eventos = eventos; this.topics = topics; this.tycVigente = tycVigente;
        this.urlFront = urlFront;
    }

    public record PayloadAlumnoRegistrado(String userId, String legajo, String invitationCode) { }

    @Transactional
    public void registrarAlumno(String firstNames, String lastNames, String legajo, String email,
                                String password, String invitationCode, String termsVersion) {
        User u = crear(firstNames, lastNames, email, password, Role.STUDENT, termsVersion);
        u.setLegajo(legajo);
        repo.saveAndFlush(u);
        // The invitation code is NOT persisted in users: it is Cursos' data.
        // It lives ephemerally in Redis until the account is activated, and travels in the event.
        guardarCodigoInvitacion(u, invitationCode);
        enviarEnlaceActivacion(u);
    }

    @Transactional
    public void registrarProfesor(String firstNames, String lastNames, String email,
                                  String password, String termsVersion) {
        String normalizado = email.toLowerCase(Locale.ROOT);
        if (!whitelist.existsByEmailAndDeletedAtIsNull(normalizado)) {
            throw ApiException.emailNotWhitelisted(
                    "El email no esta en la lista blanca. Pedile a un ADMIN que lo agregue.");
        }
        User u = crear(firstNames, lastNames, email, password, Role.PROFESSOR, termsVersion);
        repo.saveAndFlush(u);
        enviarEnlaceActivacion(u);
    }

    private User crear(String firstNames, String lastNames, String email, String password,
                       Role role, String termsVersion) {
        // DEC-31 / RF-NFR-09: obligatorio y versionado.
        if (termsVersion == null || !termsVersion.equals(tycVigente)) {
            throw ApiException.validation(
                    "Hay que aceptar los Terminos y Condiciones vigentes (version " + tycVigente + ").");
        }
        PasswordPolicy.validate(password);

        String normalizado = email.toLowerCase(Locale.ROOT);
        if (repo.findByEmailAndDeletedAtIsNull(normalizado).isPresent()) {
            throw ApiException.duplicateEmail();
        }
        return User.create(firstNames, lastNames, normalizado, encoder.encode(password), role, termsVersion);
    }

    /**
     * RF-USR-04 - proving possession of the e-mail is a single-use LINK, not a
     * typed code. The token is 256 bits: there is nothing to brute-force, so the
     * TTL can be 24 h and no attempt counter is needed.
     *
     * Se guarda HASHEADO y bajo dos claves:
     *   activacion:{sha256(token)}  -> userId   (por donde entra el enlace)
     *   activacion:email:{email}    -> sha256   (indice, para invalidar al reenviar)
     * Sin el indice, cada reenvio dejaria vivo tambien el enlace viejo.
     */
    private void enviarEnlaceActivacion(User u) {
        // Reenvio: el enlace anterior muere. Nunca hay dos validos a la vez.
        efimeros.consumir(claveIndice(u.getEmail()))
                .ifPresent(hashViejo -> efimeros.consumir(claveActivacion(hashViejo)));

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hashear(token);

        efimeros.guardar(claveActivacion(hash), u.getId().toString(), TTL_ACTIVACION);
        efimeros.guardar(claveIndice(u.getEmail()), hash, TTL_ACTIVACION);

        // El enlace apunta al FRONTEND, no a la API. Los escaneres de correo
        // institucional (Safe Links, Proofpoint) visitan los enlaces antes que
        // la persona: si activara con un GET, el escaner consumiria el token de
        // toda la cohorte. La pantalla del frontend solo activa cuando alguien
        // aprieta el boton, y ningun escaner hace eso.
        mails.enviar(EmailType.ACCOUNT_ACTIVATION, u.getEmail(),
                Map.of("firstNames", u.getFirstNames(),
                       "enlace", urlFront + "/activate?token=" + token));
    }

    @Transactional
    public void activate(String token) {
        UUID userId = efimeros.consumir(claveActivacion(hashear(token)))   // un solo uso, atomico
                .map(UUID::fromString)
                .orElseThrow(ApiException::invalidLink);

        User u = repo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::invalidLink);
        efimeros.consumir(claveIndice(u.getEmail()));   // limpia el indice
        u.activate();
        repo.save(u);

        if (u.getRole() == Role.STUDENT) {
            // DEC-34: publicamos al pasar a PENDING_COURSE, no antes. Si nunca
            // verifica el mail, Cursos nunca se entera de que existio el alta.
            eventos.publicar(topics.alumnoRegistrado(), "ALUMNO_REGISTRADO",
                    new PayloadAlumnoRegistrado(u.getId().toString(), u.getLegajo(),
                            leerCodigoInvitacion(u)));
            mails.enviar(EmailType.REQUEST_PENDING, u.getEmail(),
                    Map.of("firstNames", u.getFirstNames(),
                           "accountStatus", AccountStatus.PENDING_COURSE.name()));
        }
    }

    /** Anti-enumeration: the same response whether or not the account exists. */
    @Transactional
    public String reenviarActivacion(String email) {
        repo.findByEmailAndDeletedAtIsNull(email.toLowerCase(Locale.ROOT))
                .filter(u -> u.getAccountStatus() == AccountStatus.PENDING_EMAIL)
                .ifPresent(this::enviarEnlaceActivacion);
        return RESPUESTA_CONSTANTE;
    }

    private String claveActivacion(String hash) { return "activacion:" + hash; }

    private String claveIndice(String email) { return "activacion:email:" + email; }

    private String claveInvitacion(String email) { return "invitacion:" + email; }

    /**
     * The token is stored hashed: whoever can read Redis must not be able to
     * activate cuentas ajenas. SHA-256 pelado alcanza: el token ya es aleatorio
     * de 256 bits, ningun diccionario le sirve a nadie.
     */
    private String hashear(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tiene que existir", e);
        }
    }

    /**
     * El codigo de invitacion NO es dato nuestro: vive de forma efimera hasta
     * que la cuenta se activa, con el mismo TTL que el enlace (no el del OTP:
     * si el enlace vive 24 h y el codigo 30 min, una activacion tardia publica
     * ALUMNO_REGISTRADO con invitationCode null y Cursos no puede rutear la
     * solicitud). Se guarda con EphemeralTokenService (la puerta hacia Redis
     * que users/ tiene permitida) y no con OtpService: ese motor es para
     * codigos de 6 digitos verificados por una persona, no para pasar un dato
     * de un paso al otro del mismo flujo.
     */
    private void guardarCodigoInvitacion(User u, String code) {
        efimeros.guardar(claveInvitacion(u.getEmail()), code, TTL_ACTIVACION);
    }

    private String leerCodigoInvitacion(User u) {
        return efimeros.consumir(claveInvitacion(u.getEmail())).orElse(null);
    }
}
