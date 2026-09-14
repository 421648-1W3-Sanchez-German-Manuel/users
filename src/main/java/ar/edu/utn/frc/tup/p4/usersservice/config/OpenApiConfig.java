package ar.edu.utn.frc.tup.p4.usersservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El spec de OpenAPI del servicio.
 *
 * <p><b>Por que el esquema es Bearer y no los headers X-*.</b> Este servicio NO
 * valida JWT (DEC-08): recibe la identidad ya resuelta en {@code X-User-Id} /
 * {@code X-User-Roles}, que inyecta el gateway. La tentacion es documentar esos
 * headers como el mecanismo de auth, y estaria mal por dos motivos:
 *
 * <ol>
 *   <li>Quien lee esta pantalla la abre por nginx:3000, asi que su "Try it out"
 *       sale por nginx -> gateway -> aca. Lo que tiene que mandar es el Bearer;
 *       los X-* los pone el gateway en el camino.</li>
 *   <li>Documentar los X-* como algo que el cliente manda es documentar el
 *       agujero del no-negociable 1: esos headers son reservados y el gateway
 *       los pisa. Un ejemplo copiable que los mande a mano es una invitacion a
 *       intentar justamente lo que el diseño prohibe.</li>
 * </ol>
 *
 * <p>No se declara {@code servers}: el spec se sirve bajo el mismo origen que
 * la API (nginx:3000), asi que Swagger UI resuelve relativo y "Try it out"
 * funciona sin configurar nada. Una URL absoluta aca rompe el dia que cambie
 * el puerto o el host del compose.
 */
@Configuration
public class OpenApiConfig {

    /** El nombre con el que los controllers privados referencian el esquema. */
    public static final String BEARER_SCHEME = "bearerAuth";

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
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        El access token que devuelve `POST /api/users/public/auth/2fa/verify`.
                                        Lo valida el GATEWAY, no este servicio.

                                        Ojo con dos trampas conocidas al probar desde aca:
                                        esperar ~4 s despues del login (el gateway cachea el
                                        estado de sesion 3 s) y que el primer login del stack
                                        puede dar 503 por BCrypt cost 12 en una JVM fria.""")));
    }
}
