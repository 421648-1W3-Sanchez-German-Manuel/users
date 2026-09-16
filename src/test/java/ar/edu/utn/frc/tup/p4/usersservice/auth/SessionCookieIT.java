package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.services.SessionCookieService;
import ar.edu.utn.frc.tup.p4.usersservice.config.CookieProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.IdentityHeaders;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginIT y SingleSessionRefreshIT llaman a AuthService directo y nunca ven
 * un header HTTP. Esto es lo unico que mira lo que el NAVEGADOR recibe de
 * verdad: los Set-Cookie, con sus atributos. El gateway y el front dependen
 * de HttpOnly/Path/SameSite (SessionCookieService) para que la cookie ni se
 * lea por JS ni se mande a otra ruta, y de que el logout la borre con el
 * MISMO Path con el que se emitio.
 *
 * <p>RANDOM_PORT + HttpClient del JDK, mismo criterio que OpenApiIT: esto
 * depende de headers HTTP crudos (varios Set-Cookie en la misma respuesta),
 * que MockMvc no expone igual que un socket real.
 */
@Import(TestOtpSpy.Config.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieIT extends AbstractIntegrationTest {

    private static final String SUF = "-" + UUID.randomUUID() + "@utn.edu.ar";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Autowired TestOtpSpy otpSpy;
    @Autowired CookieProperties cookieProps;

    private User crearActivo(String email, String password) {
        User u = User.create("Ana", "Perez", email, encoder.encode(password), Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u);
    }

    private HttpResponse<String> post(String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookieDe(HttpResponse<String> res, String nombre) {
        return res.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith(nombre + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no vino Set-Cookie de " + nombre));
    }

    /** Assert comun: HttpOnly, SameSite=Strict y Secure segun CookieProperties. */
    private void assertAtributosComunes(String cookie) {
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict");
        if (cookieProps.secure()) {
            assertThat(cookie).contains("Secure");
        } else {
            assertThat(cookie).doesNotContain("Secure");
        }
    }

    @Test
    void el_2fa_verify_setea_fu_at_y_fu_rt_httponly_con_su_propio_path() throws Exception {
        crearActivo("cookie1" + SUF, "passwordvalida1");

        var loginRes = post("/api/users/public/auth/login",
                "{\"email\":\"cookie1" + SUF + "\",\"password\":\"passwordvalida1\"}");
        String challengeId = JSON.readTree(loginRes.body()).get("challengeId").asText();

        var verifyRes = post("/api/users/public/auth/2fa/verify",
                "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + otpSpy.ultimoCodigo() + "\"}");

        assertThat(verifyRes.statusCode()).isEqualTo(200);
        // El body ya NO lleva los tokens (SessionResponse): solo expiresIn.
        assertThat(verifyRes.body()).doesNotContain("accessToken").doesNotContain("refreshToken");

        List<String> setCookies = verifyRes.headers().allValues("set-cookie");
        assertThat(setCookies).hasSize(2);

        String accessCookie = cookieDe(verifyRes, SessionCookieService.ACCESS_COOKIE);
        assertAtributosComunes(accessCookie);
        assertThat(accessCookie).contains("Path=/;");

        String refreshCookie = cookieDe(verifyRes, SessionCookieService.REFRESH_COOKIE);
        assertAtributosComunes(refreshCookie);
        assertThat(refreshCookie).contains("Path=/api/users/;");
    }

    @Test
    void el_refresh_rota_fu_rt_y_lo_manda_con_el_mismo_path() throws Exception {
        crearActivo("cookie2" + SUF, "passwordvalida1");

        var loginRes = post("/api/users/public/auth/login",
                "{\"email\":\"cookie2" + SUF + "\",\"password\":\"passwordvalida1\"}");
        String challengeId = JSON.readTree(loginRes.body()).get("challengeId").asText();
        var verifyRes = post("/api/users/public/auth/2fa/verify",
                "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + otpSpy.ultimoCodigo() + "\"}");
        String refreshCookieValor = extraerValor(cookieDe(verifyRes, SessionCookieService.REFRESH_COOKIE));

        var refreshRes = post("/api/users/public/auth/refresh", "",
                "Cookie", SessionCookieService.REFRESH_COOKIE + "=" + refreshCookieValor);

        assertThat(refreshRes.statusCode()).isEqualTo(200);
        String nuevoRefresh = cookieDe(refreshRes, SessionCookieService.REFRESH_COOKIE);
        assertAtributosComunes(nuevoRefresh);
        assertThat(nuevoRefresh).contains("Path=/api/users/;");
        // Rotado: el jti nuevo no es el que mando.
        assertThat(extraerValor(nuevoRefresh)).isNotEqualTo(refreshCookieValor);
    }

    @Test
    void un_fu_rt_malformado_da_400_validation_y_no_llega_a_redis() throws Exception {
        var refreshRes = post("/api/users/public/auth/refresh", "",
                "Cookie", SessionCookieService.REFRESH_COOKIE + "=no-es-un-uuid");

        assertThat(refreshRes.statusCode()).isEqualTo(400);
        assertThat(refreshRes.body()).contains("validation");
    }

    @Test
    void el_logout_limpia_fu_at_y_fu_rt_con_maxAge_cero_y_el_mismo_path() throws Exception {
        User u = crearActivo("cookie3" + SUF, "passwordvalida1");

        var logoutRes = post("/api/users/auth/logout", "",
                IdentityHeaders.PRINCIPAL_TYPE, "user",
                IdentityHeaders.USER_ID, u.getId().toString(),
                IdentityHeaders.USER_ROLES, Role.STUDENT.name());

        assertThat(logoutRes.statusCode()).isEqualTo(200);

        String accessClear = cookieDe(logoutRes, SessionCookieService.ACCESS_COOKIE);
        assertAtributosComunes(accessClear);
        assertThat(accessClear).contains("Path=/;").contains("Max-Age=0");

        String refreshClear = cookieDe(logoutRes, SessionCookieService.REFRESH_COOKIE);
        assertAtributosComunes(refreshClear);
        assertThat(refreshClear).contains("Path=/api/users/;").contains("Max-Age=0");
    }

    private String extraerValor(String setCookieHeader) {
        String sinNombre = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        return sinNombre.substring(0, sinNombre.indexOf(';'));
    }
}
