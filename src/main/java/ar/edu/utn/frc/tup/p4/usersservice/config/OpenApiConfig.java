package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.auth.services.SessionCookieService;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * El spec de OpenAPI del servicio.
 *
 * <p><b>Por que el esquema es una cookie y no Bearer ni los headers X-*.</b>
 * Este servicio NO valida JWT (DEC-08): recibe la identidad ya resuelta en
 * {@code X-User-Id} / {@code X-User-Roles}, que inyecta el gateway. Documentar
 * esos headers como el mecanismo de auth estaria mal por dos motivos:
 *
 * <ol>
 *   <li>Quien lee esta pantalla la abre por nginx:3000, asi que su "Try it out"
 *       sale por nginx -> gateway -> aca. Los X-* los pone el gateway en el
 *       camino, el cliente nunca los manda.</li>
 *   <li>Documentar los X-* como algo que el cliente manda es documentar el
 *       agujero del no-negociable 1: esos headers son reservados y el gateway
 *       los pisa. Un ejemplo copiable que los mande a mano es una invitacion a
 *       intentar justamente lo que el diseño prohibe.</li>
 * </ol>
 *
 * <p>Tampoco es Bearer por header: el gateway (spec "Sesion en Cookies",
 * decision 3 de {@code PrivateRouteGuard}) exige que un principal de tipo
 * persona llegue por la cookie {@code fu_at} y rechaza con 401 el mismo token
 * si viaja por {@code Authorization}. Documentar Bearer invitaria a pegar el
 * access token en el header de Swagger UI y ver un 401 enganoso. Como esta
 * pantalla se abre bajo el mismo origen que la API, el navegador ya manda la
 * cookie sola despues del login; no hay nada que tipear.
 *
 * <p>No se declara {@code servers}: el spec se sirve bajo el mismo origen que
 * la API (nginx:3000), asi que Swagger UI resuelve relativo y "Try it out"
 * funciona sin configurar nada. Una URL absoluta aca rompe el dia que cambie
 * el puerto o el host del compose.
 */
@Configuration
public class OpenApiConfig {

    /** El nombre con el que los controllers privados referencian el esquema. */
    public static final String COOKIE_SCHEME = "cookieAuth";

    @Bean
    OpenAPI usersServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("users-service · Tema 01 · Identidad y Usuarios")
                        .version("v1")
                        .description("""
                                Dueño de la identidad de la plataforma: registro, credenciales,
                                sesiones, roles y emision de tokens.

                                **Todo entra por el API gateway.** Este servicio no publica
                                puertos y no valida el JWT: confia en los headers `X-*` que le
                                inyecta el gateway despues de validar la firma y la sesion.

                                **Los errores son siempre `application/problem+json`** y se
                                discriminan por el campo `type`, nunca por el status. Los `type`
                                viven bajo `https://tpi.utn.frc/errors/`.

                                Rutas bajo `/api/users/public/**` son anonimas; el resto exige
                                un access token vigente."""))
                .components(new Components().addSecuritySchemes(COOKIE_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(SessionCookieService.ACCESS_COOKIE)
                                .description("""
                                        La cookie HttpOnly que deja `POST /api/users/public/auth/2fa/verify`
                                        (o `/refresh`). Lo valida el GATEWAY, no este servicio.

                                        NO va por header `Authorization`: el gateway rechaza con 401 un
                                        token de persona que llegue por ahi (decision 3, spec "Sesion en
                                        Cookies"). Como esta pantalla se abre bajo el mismo origen que la
                                        API, el navegador manda la cookie solo despues de loguearte aca -
                                        no hace falta pegar nada en "Authorize".

                                        Ojo con dos trampas conocidas al probar desde aca:
                                        esperar ~4 s despues del login (el gateway cachea el
                                        estado de sesion 3 s) y que el primer login del stack
                                        puede dar 503 por BCrypt cost 12 en una JVM fria.""")));
    }

    /** El nombre del schema reusable del cuerpo de error. */
    private static final String PROBLEM = "ProblemDetail";

    /**
     * Las respuestas de error que tiene TODO endpoint, agregadas de una vez.
     *
     * <p>El contrato de errores es uniforme en el servicio entero
     * (no-negociable 2): mismo media type, mismo cuerpo, y el cliente ramifica
     * por {@code type}. Repetir cuatro {@code @ApiResponse} en cada uno de los
     * ~28 metodos seria copiar 112 veces una decision que se toma una sola vez,
     * y alcanzaria con que alguien se olvide en el endpoint nuevo para que la
     * doc diga que ese no falla igual que el resto.
     *
     * <p>El 401/403 se agrega SOLO a lo privado. Ponerlo en todo seria mentir
     * justo en la direccion mas cara: sugiere que una ruta publica puede
     * contestar 401, que es exactamente lo que el no-negociable 3 prohibe.
     */
    @Bean
    OpenApiCustomizer respuestasDeError(@Value("${app.api.public-path}") String publicPath) {
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM, problemDetailSchema());

            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
                // Hasta un GET sin body puede dar 400: un {id} que no convierte
                // a UUID entra por el mismo @RestControllerAdvice.
                completar(op, "400", "Validacion fallida. `type`: `validation`.");

                if (esPrivada(path, publicPath)) {
                    completar(op, "401", "Sin identidad valida. `type`: `not-authenticated`.");
                    completar(op, "403",
                            "Rol insuficiente, o una cuenta frenada por un gate. `type`: "
                            + "`access-denied`, `pending-account`, `password-change-required` "
                            + "u `onboarding-pending`.");
                }
            }));
        };
    }

    /**
     * El JWKS no lleva el prefijo publico -es una convencion web (RFC 8615), no
     * del proyecto- pero es anonimo igual: SecurityConfig lo deja pasar con
     * permitAll. Sin esta excepcion quedaria documentado como si pudiera
     * contestar 401.
     */
    private boolean esPrivada(String path, String publicPath) {
        return !path.startsWith(publicPath) && !path.startsWith("/.well-known");
    }

    /**
     * Rellena el codigo SOLO si el endpoint no lo documento ya.
     *
     * <p>Esto no es una optimizacion, es correctitud: varios endpoints tienen un
     * 400 que NO es `validation` -{@code invalid-code} e {@code invalid-link}
     * tambien son 400- y describirlo es justamente lo que hace util la pagina.
     * Un {@code addApiResponse} a secas los pisaria, porque el customizer corre
     * DESPUES de que springdoc leyo las anotaciones, y la doc diria "validacion
     * fallida" en el endpoint donde el 400 significa "el enlace vencio".
     */
    private void completar(io.swagger.v3.oas.models.Operation op, String codigo, String descripcion) {
        if (op.getResponses().get(codigo) == null) {
            op.getResponses().addApiResponse(codigo, problema(descripcion));
        }
    }

    private ApiResponse problema(String descripcion) {
        return new ApiResponse()
                .description(descripcion)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
    }

    private Schema<?> problemDetailSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.description("""
                RFC 7807. El cliente ramifica por `type`, NUNCA por el status: dos
                errores con el mismo 403 pueden necesitar pantallas distintas.
                Todos los `type` cuelgan de `https://tpi.utn.frc/errors/`.""");
        schema.properties(Map.of(
                "type", new StringSchema()
                        .description("La identidad del error. Es el campo por el que se ramifica.")
                        .example("https://tpi.utn.frc/errors/invalid-credentials"),
                "title", new StringSchema().example("Credenciales invalidas"),
                "status", new Schema<Integer>().type("integer").format("int32").example(401),
                "detail", new StringSchema().example("El email o la contraseña no coinciden."),
                "instance", new StringSchema()
                        .description("El path del request que fallo.")
                        .example("/api/users/public/auth/login")));
        return schema;
    }
}
