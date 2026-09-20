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
 * LoginIT and SingleSessionRefreshIT call AuthService directly and never see an
 * HTTP header. This is the only test that inspects what the BROWSER actually
 * receives: the Set-Cookie headers and their attributes. The gateway and
 * frontend rely on HttpOnly/Path/SameSite (SessionCookieService) to prevent JS
 * from reading the cookie or sending it to another path, and on logout clearing
 * it with the SAME Path used when it was issued.
 *
 * <p>RANDOM_PORT plus the JDK HttpClient follows the same reasoning as OpenApiIT:
 * this depends on raw HTTP headers (multiple Set-Cookie headers in one response),
 * which MockMvc does not expose like a real socket.
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
    @Autowired CookieProperties cookieProperties;

    private User createActiveUser(String email, String password) {
        User user = User.create("Ana", "Perez", email, encoder.encode(password), Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(user);
    }

    private HttpResponse<String> post(String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookieFrom(HttpResponse<String> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Set-Cookie received for " + name));
    }

    /** Common assertion: HttpOnly, SameSite=Strict, and Secure according to CookieProperties. */
    private void assertCommonAttributes(String cookie) {
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict");
        if (cookieProperties.secure()) {
            assertThat(cookie).contains("Secure");
        } else {
            assertThat(cookie).doesNotContain("Secure");
        }
    }

    @Test
    void twoFactorVerificationSetsHttpOnlyAccessAndRefreshCookiesWithTheirOwnPaths() throws Exception {
        createActiveUser("cookie1" + SUF, "validpassword1");

        var loginResponse = post("/api/users/public/auth/login",
                "{\"email\":\"cookie1" + SUF + "\",\"password\":\"validpassword1\"}");
        String challengeId = JSON.readTree(loginResponse.body()).get("challengeId").asText();

        var verificationResponse = post("/api/users/public/auth/2fa/verify",
                "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + otpSpy.lastCode() + "\"}");

        assertThat(verificationResponse.statusCode()).isEqualTo(200);
        // The body NO longer contains tokens (SessionResponse), only expiresIn.
        assertThat(verificationResponse.body()).doesNotContain("accessToken").doesNotContain("refreshToken");

        List<String> setCookies = verificationResponse.headers().allValues("set-cookie");
        assertThat(setCookies).hasSize(2);

        String accessCookie = cookieFrom(verificationResponse, SessionCookieService.ACCESS_COOKIE);
        assertCommonAttributes(accessCookie);
        assertThat(accessCookie).contains("Path=/;");

        String refreshCookie = cookieFrom(verificationResponse, SessionCookieService.REFRESH_COOKIE);
        assertCommonAttributes(refreshCookie);
        assertThat(refreshCookie).contains("Path=/api/users/;");
    }

    @Test
    void refreshRotatesTheRefreshCookieAndSendsItWithTheSamePath() throws Exception {
        createActiveUser("cookie2" + SUF, "validpassword1");

        var loginResponse = post("/api/users/public/auth/login",
                "{\"email\":\"cookie2" + SUF + "\",\"password\":\"validpassword1\"}");
        String challengeId = JSON.readTree(loginResponse.body()).get("challengeId").asText();
        var verificationResponse = post("/api/users/public/auth/2fa/verify",
                "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + otpSpy.lastCode() + "\"}");
        String refreshCookieValue = extractValue(
                cookieFrom(verificationResponse, SessionCookieService.REFRESH_COOKIE));

        var refreshResponse = post("/api/users/public/auth/refresh", "",
                "Cookie", SessionCookieService.REFRESH_COOKIE + "=" + refreshCookieValue);

        assertThat(refreshResponse.statusCode()).isEqualTo(200);
        String newRefreshCookie = cookieFrom(refreshResponse, SessionCookieService.REFRESH_COOKIE);
        assertCommonAttributes(newRefreshCookie);
        assertThat(newRefreshCookie).contains("Path=/api/users/;");
        // It was rotated: the new jti differs from the one sent.
        assertThat(extractValue(newRefreshCookie)).isNotEqualTo(refreshCookieValue);
    }

    @Test
    void malformedRefreshCookieReturns400ValidationWithoutReachingRedis() throws Exception {
        var refreshResponse = post("/api/users/public/auth/refresh", "",
                "Cookie", SessionCookieService.REFRESH_COOKIE + "=not-a-uuid");

        assertThat(refreshResponse.statusCode()).isEqualTo(400);
        assertThat(refreshResponse.body()).contains("validation");
    }

    @Test
    void logoutClearsAccessAndRefreshCookiesWithZeroMaxAgeAndTheSamePath() throws Exception {
        User user = createActiveUser("cookie3" + SUF, "validpassword1");

        var logoutResponse = post("/api/users/auth/logout", "",
                IdentityHeaders.PRINCIPAL_TYPE, "user",
                IdentityHeaders.USER_ID, user.getId().toString(),
                IdentityHeaders.USER_ROLES, Role.STUDENT.name());

        assertThat(logoutResponse.statusCode()).isEqualTo(200);

        String accessClear = cookieFrom(logoutResponse, SessionCookieService.ACCESS_COOKIE);
        assertCommonAttributes(accessClear);
        assertThat(accessClear).contains("Path=/;").contains("Max-Age=0");

        String refreshClear = cookieFrom(logoutResponse, SessionCookieService.REFRESH_COOKIE);
        assertCommonAttributes(refreshClear);
        assertThat(refreshClear).contains("Path=/api/users/;").contains("Max-Age=0");
    }

    private String extractValue(String setCookieHeader) {
        String withoutName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);
        return withoutName.substring(0, withoutName.indexOf(';'));
    }
}
