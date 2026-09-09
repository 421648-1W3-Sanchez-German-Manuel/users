package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import java.net.URI;

/** The same prefix the gateway uses: a single namespace for errors. */
public final class ErrorTypes {
    private static final String BASE = "https://tpi.utn.frc/errors/";

    public static final URI VALIDATION               = URI.create(BASE + "validation");
    /**
     * The SAME type the gateway uses for a request with no valid identity.
     * Here it applies when a request arrives WITHOUT the X-* headers: it should
     * not happen in production (the gateway cuts first), but if it does the
     * response has to be identical no matter where it came from.
     */
    public static final URI NOT_AUTHENTICATED           = URI.create(BASE + "not-authenticated");
    public static final URI INVALID_CREDENTIALS   = URI.create(BASE + "invalid-credentials");
    /**
     * The SAME two types the gateway uses when the token's session id is not
     * the current one. Here the refresh emits them: a refresh token that no
     * longer works is not a wrong credential, it is a session that stopped
     * existing, and the message the person reads has to say that rather than
     * "wrong username or password".
     */
    public static final URI SESSION_CLOSED           = URI.create(BASE + "session-closed");
    public static final URI SESSION_SUPERSEDED          = URI.create(BASE + "session-superseded");
    /** A route that does not exist in this service. The SAME type the gateway uses. */
    public static final URI ROUTE_NOT_FOUND         = URI.create(BASE + "route-not-found");
    public static final URI PENDING_ACCOUNT         = URI.create(BASE + "pending-account");
    public static final URI PASSWORD_CHANGE_REQUIRED= URI.create(BASE + "password-change-required");
    public static final URI ONBOARDING_PENDING     = URI.create(BASE + "onboarding-pending");
    public static final URI EMAIL_NOT_WHITELISTED      = URI.create(BASE + "email-not-whitelisted");
    public static final URI ACCESS_DENIED          = URI.create(BASE + "access-denied");
    public static final URI LAST_ADMIN             = URI.create(BASE + "last-admin");
    public static final URI INVALID_TRANSITION      = URI.create(BASE + "invalid-transition");
    public static final URI DUPLICATE_EMAIL          = URI.create(BASE + "duplicate-email");
    public static final URI INVALID_CODE          = URI.create(BASE + "invalid-code");
    /**
     * A single-use link (account activation, password reset) that is expired,
     * already used or non-existent. Kept apart from `invalid-code` because
     * the screen and the way out differ: a code is typed again, a link has to
     * be requested anew.
     */
    public static final URI INVALID_LINK          = URI.create(BASE + "invalid-link");
    /** DEC-24: the SAME type the gateway uses for its per-IP limit. */
    public static final URI TOO_MANY_ATTEMPTS      = URI.create(BASE + "too-many-attempts");

    private ErrorTypes() { }
}
