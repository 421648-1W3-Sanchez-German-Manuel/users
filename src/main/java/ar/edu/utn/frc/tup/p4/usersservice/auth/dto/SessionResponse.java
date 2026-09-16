package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

/**
 * Lo que recibe el cliente tras login/refresh. Nunca los tokens: viajan en
 * las cookies HttpOnly fu_at/fu_rt (SessionCookieService), invisibles para
 * JS a proposito. expiresIn es lo unico que el front todavia necesita, para
 * el timer de refresh proactivo.
 */
public record SessionResponse(long expiresIn) {
    public static SessionResponse from(TokenResponse tokens) {
        return new SessionResponse(tokens.expiresIn());
    }
}
