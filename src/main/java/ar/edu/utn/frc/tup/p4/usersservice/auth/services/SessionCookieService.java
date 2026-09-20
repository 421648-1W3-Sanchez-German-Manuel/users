package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.config.CookieProperties;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The only place that builds session Set-Cookie headers ("Cookie Sessions"
 * specification, S02.1). fu_rt uses Path=/api/users/ rather than Path=/ because
 * a cookie supports only one Path value: this is the narrowest prefix covering
 * both routes that consume it (/api/users/public/auth/refresh and
 * /api/users/auth/logout).
 *
 * Logout must clear the cookie with the SAME Path used when issuing it; see
 * {@link #clearAccess()} and {@link #clearRefresh()}. Otherwise, the browser
 * treats it as a different cookie and leaves the old one active.
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

    /** A Set-Cookie header with Max-Age=0 instructs the browser to delete it immediately. */
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
