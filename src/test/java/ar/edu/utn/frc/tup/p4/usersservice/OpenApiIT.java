package ar.edu.utn.frc.tup.p4.usersservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El spec de OpenAPI y la pantalla de Swagger UI se sirven de verdad.
 *
 * <p><b>Por que RANDOM_PORT y no el MOCK que hereda la clase base.</b> La mitad
 * de lo que se verifica aca son RECURSOS ESTATICOS (el webjar de Swagger UI),
 * y este servicio tiene dos flags que existen para que un 404 salga en
 * problem+json:
 *
 * <pre>
 *   spring.web.resources.add-mappings: false
 *   spring.mvc.throw-exception-if-no-handler-found: true
 * </pre>
 *
 * El primero NO es inocente para springdoc: {@code AbstractSwaggerConfigurer.
 * getSwaggerWebjarHandlerConfigs()} llama a {@code WebProperties.Resources.
 * isAddMappings()} y, si da false, devuelve un array VACIO — o sea que springdoc
 * se rinde y no registra los handlers del prefijo /webjars. Lo que salva la
 * pantalla es que {@code getSwaggerHandlerConfigs()}, el que sirve
 * {uiRootPath}/swagger-ui/**, NO mira ese flag y se registra igual.
 *
 * <p>Esa asimetria es exactamente lo que cubre {@link #los_recursos_estaticos_de_swagger_ui_se_sirven()}.
 * Si alguien "ordena" la config y toca alguno de los dos flags, o si springdoc
 * cambia de opinion en una version futura, esto se pone rojo. Sin este test el
 * sintoma seria una pantalla en blanco, que nadie asocia con un flag de
 * recursos estaticos puesto por el tema de los 404.
 *
 * <p>El cliente es el del JDK y no un TestRestTemplate: Boot 4 lo saco de
 * spring-boot-test y vive en un modulo aparte que este pom no trae. Sumar una
 * dependencia de test para cuatro GET no se justifica.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiIT extends AbstractIntegrationTest {

    @LocalServerPort int port;

    private static final String SPEC = "/api/users/public/v3/api-docs";
    private static final String UI = "/api/users/public/docs";
    private static final String UI_INDEX = "/api/users/public/swagger-ui/index.html";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * TODAS las rutas del servicio. La lista esta a mano a proposito: si alguien
     * agrega un endpoint, springdoc lo detecta solo y este test NO se entera,
     * pero si alguien MUEVE un prefijo (el caso que duele: sacar la doc de
     * /api/users/public y dejarla invisible detras de nginx) se pone rojo.
     */
    private static final List<String> RUTAS = List.of(
            "/api/users/public/auth/login",
            "/api/users/public/auth/2fa/verify",
            "/api/users/public/auth/refresh",
            "/api/users/public/auth/token",
            "/api/users/public/auth/password/reset",
            "/api/users/public/auth/password/reset/confirm",
            "/api/users/public/registration/student",
            "/api/users/public/registration/professor",
            "/api/users/public/registration/activate",
            "/api/users/public/registration/resend-activation",
            "/api/users/public/legal/terms",
            "/api/users/auth/logout",
            "/api/users/auth/password/change",
            "/api/users/me",
            "/api/users/me/onboarding",
            "/api/users/profile/{id}",
            "/api/users",
            "/api/users/{id}",
            "/api/users/{id}/role",
            "/api/users/whitelist",
            "/api/users/whitelist/{id}",
            "/api/users/whitelist/requests",
            "/api/users/whitelist/requests/{id}",
            "/.well-known/jwks.json");

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void el_spec_se_genera_y_tiene_todas_las_rutas() throws Exception {
        HttpResponse<String> res = get(SPEC);

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode paths = JSON.readTree(res.body()).get("paths");
        assertThat(paths).isNotNull();
        RUTAS.forEach(ruta -> assertThat(paths.has(ruta))
                .as("falta la ruta %s en el spec", ruta).isTrue());
    }

    @Test
    void el_spec_no_se_sirve_fuera_del_prefijo_publico() throws Exception {
        // La razon de todo el diseño: el gateway solo deja pasar sin JWT lo que
        // matchea /api/*/public/**, y nginx solo proxea /api/. Si el spec deja
        // de colgar de ahi, deja de verse desde el navegador — y el sintoma es
        // un 401 o el index.html del front, nunca "moviste el path".
        //
        // No se fija el codigo exacto: el path default cae en `anyRequest()
        // .authenticated()`, asi que hoy da 401 y no 404. Lo que importa es que
        // NO sirva el spec.
        assertThat(SPEC).startsWith("/api/users/public/");
        assertThat(get("/v3/api-docs").statusCode())
                .as("el path default de springdoc no tiene que servir el spec")
                .isNotEqualTo(200);
    }

    @Test
    void el_spec_declara_el_esquema_bearer() throws Exception {
        JsonNode esquema = JSON.readTree(get(SPEC).body())
                .at("/components/securitySchemes/bearerAuth");

        assertThat(esquema.isMissingNode()).isFalse();
        assertThat(esquema.get("scheme").asText()).isEqualTo("bearer");
    }


    @Test
    void la_pantalla_de_swagger_ui_redirige_al_index() throws Exception {
        // OJO con el path de los assets: NO cuelga de swagger-ui.path sino de
        // su PADRE. Con path=/api/users/public/docs, el uiRootPath que arma
        // springdoc es /api/users/public y el index queda en
        // /api/users/public/swagger-ui/index.html, no en /docs/swagger-ui/...
        //
        // Que caiga igual dentro del prefijo publico es lo que hace que esto
        // funcione detras de nginx y del gateway. Un swagger-ui.path con un
        // segmento menos (ej: /api/docs) manda los assets a /api/swagger-ui/**,
        // fuera de /api/*/public/** -> 401 del gateway, pantalla en blanco.
        HttpResponse<String> redir = get(UI);

        assertThat(redir.statusCode()).isEqualTo(302);
        assertThat(redir.headers().firstValue("location")).contains(UI_INDEX);
        assertThat(UI_INDEX).startsWith("/api/users/public/");
    }

    @Test
    void los_recursos_estaticos_de_swagger_ui_se_sirven() throws Exception {
        // EL test del riesgo de add-mappings: esto es un recurso ESTATICO del
        // webjar, no un @RestController. Si springdoc se rinde, aca sale 404.
        // Se piden los dos: el index y el bundle, porque el index lo puede
        // servir el IndexPageTransformer y el bundle no — si solo se probara el
        // index, una pantalla en blanco pasaria el test.
        assertThat(get(UI_INDEX).statusCode()).isEqualTo(200);
        assertThat(get(UI_INDEX).body()).contains("swagger-ui");

        HttpResponse<String> bundle = get("/api/users/public/swagger-ui/swagger-ui-bundle.js");
        assertThat(bundle.statusCode()).isEqualTo(200);
    }

    @Test
    void el_swagger_config_apunta_al_spec_propio() throws Exception {
        // Es el JSON que la pantalla pide para saber que spec cargar. Si esto
        // no resuelve, la UI abre vacia aunque el spec exista.
        HttpResponse<String> cfg = get(SPEC + "/swagger-config");

        assertThat(cfg.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(cfg.body()).toString()).contains(SPEC);
    }
}
