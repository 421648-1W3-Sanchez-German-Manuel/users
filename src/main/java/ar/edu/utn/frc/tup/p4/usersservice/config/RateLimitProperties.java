package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;

/**
 * DEC-42 - cuenta por e-mail. El gateway limita por IP; claves distintas, asi
 * que los dos no pueden dispararse por la misma condicion.
 *
 * Tres presupuestos separados y NO intercambiables:
 *
 * <ul>
 *   <li>{@code login-*}: fallos de credenciales. Se limpia al acertar.</li>
 *   <li>{@code reset-*}: pedidos de reset. Cuenta INTENTOS, no fallos: el
 *       endpoint es publico y responde lo mismo exista o no la cuenta, asi que
 *       no hay "acierto" que pueda limpiarlo.</li>
 *   <li>{@code twofactor-*}: desafios de 2FA emitidos. Frena que alguien que ya
 *       tiene la password inunde de mails al dueno de la cuenta.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "users.ratelimit")
public record RateLimitProperties(
        @Name("login-max-failures") int loginMaxFallos,
        @Name("login-window") Duration loginVentana,
        @Name("reset-max-requests") int resetMaxPedidos,
        @Name("reset-window") Duration resetVentana,
        @Name("twofactor-max-challenges") int dosfaMaxDesafios,
        @Name("twofactor-window") Duration dosfaVentana) { }
