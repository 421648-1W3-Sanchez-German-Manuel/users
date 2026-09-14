package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.config.CookieProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Unico lugar que arma los Set-Cookie de sesion (spec "Sesion en Cookies",
 * S02.1). fu_rt usa Path=/api/users/ y no Path=/ porque una cookie admite un
 * solo valor de Path: es el prefijo mas angosto que cubre las dos rutas que
 * la consumen (/api/users/public/auth/refresh y /api/users/auth/logout).
 *
 * El logout tiene que limpiar con el MISMO Path que se emite aca -ver
 * clearAccess()/clearRefresh()- o el navegador la interpreta como una cookie
 * distinta y la vieja queda viva, sin que el logout la haya tocado.
 */
@Component
public class SessionCookieService {

    public static final String ACCESS_COOKIE = "fu_at";
    public static final String REFRESH_COOKIE = "fu_rt";
    private static final String REFRESH_PATH = "/api/users/";
    private static final String ACCESS_PATH = "/";

    private final CookieProperties cookieProps;
    private final JwtProperties jwt;

    public SessionCookieService(CookieProperties cookieProps, JwtProperties jwt) {
        this.cookieProps = cookieProps;
        this.jwt = jwt;
    }

    public ResponseCookie access(String accessToken) {
        return build(ACCESS_COOKIE, accessToken, ACCESS_PATH, jwt.accessTtl());
    }

    public ResponseCookie refresh(String refreshJti) {
        return build(REFRESH_COOKIE, refreshJti, REFRESH_PATH, jwt.refreshTtl());
    }

    /** Set-Cookie con Max-Age=0: instruye al navegador a borrarla ya. */
    public ResponseCookie clearAccess() {
        return build(ACCESS_COOKIE, "", ACCESS_PATH, Duration.ZERO);
    }

    public ResponseCookie clearRefresh() {
        return build(REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO);
    }

    private ResponseCookie build(String name, String value, String path, Duration ttl) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieProps.secure())
                .sameSite("Strict")
                .path(path)
                .maxAge(ttl)
                .build();
    }
}
