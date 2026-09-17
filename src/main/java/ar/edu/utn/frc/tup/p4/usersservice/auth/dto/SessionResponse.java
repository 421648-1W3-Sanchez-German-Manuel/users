package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

/**
 * The response received by the client after login or refresh. It never contains
 * tokens: they travel in the HttpOnly fu_at/fu_rt cookies (SessionCookieService),
 * deliberately hidden from JavaScript. expiresIn is the only value the frontend
 * still needs for the proactive refresh timer.
 */
public record SessionResponse(long expiresIn) {
    public static SessionResponse from(TokenResponse tokens) {
        return new SessionResponse(tokens.expiresIn());
    }
}
