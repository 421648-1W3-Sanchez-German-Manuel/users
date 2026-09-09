package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error this service can return, one factory per situation.
 *
 * `title` and `detail` are in English on purpose. They are the developer-facing
 * half of RFC 9457: what a log line, a test report or another team's engineer
 * reads. The sentence the student reads is NOT this one - the frontend maps
 * `type` to its own Spanish copy, so the wording can change without touching
 * the backend and a Spanish-speaking user never depends on an English string
 * leaking through.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    private ApiException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    private ApiException with(String k, Object v) { extras.put(k, v); return this; }

    public static ApiException validation(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "Invalid request", detail);
    }

    /** Anti-enumeration: the detail NEVER tells "does not exist" from "wrong password". */
    public static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorTypes.INVALID_CREDENTIALS,
                "Invalid credentials", "Wrong username or password.");
    }

    /**
     * The refresh token no longer works because the session ended: logout,
     * reuse detection or a revoked family. It is NOT "invalid credentials": the
     * person typed nothing wrong, and the frontend has to tell them to sign in
     * again.
     */
    public static ApiException sessionClosed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_CLOSED,
                "Session closed", "The session is no longer active. Sign in again.");
    }

    /** Single session: a newer login happened and this one was displaced. */
    public static ApiException sessionSuperseded() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorTypes.SESSION_SUPERSEDED,
                "Session superseded", "You signed in on another device.");
    }

    /** A route that does not exist. Never a 401: a URL typo is not an expired session. */
    public static ApiException routeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorTypes.ROUTE_NOT_FOUND,
                "Route not found", "The requested route does not exist.");
    }

    /**
     * Anti-enumeration: the same error for an expired link, an already used one
     * and one that never existed. The detail does not say which of the three
     * happened.
     */
    public static ApiException invalidLink() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorTypes.INVALID_LINK,
                "Invalid link", "The link is not valid or has expired. Request a new one.");
    }

    /** Anti-enumeration: the same error for a wrong code, an expired one and an unknown e-mail. */
    public static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorTypes.INVALID_CODE,
                "Invalid code", "The code is wrong or has expired.");
    }

    public static ApiException pendingAccount(AccountStatus status) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.PENDING_ACCOUNT,
                "Account pending validation", "The account is not active.")
                .with("accountStatus", status.name());
    }

    public static ApiException passwordChangeRequired() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.PASSWORD_CHANGE_REQUIRED,
                "Password change required", "You must change your password before continuing.");
    }

    public static ApiException onboardingPending() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.ONBOARDING_PENDING,
                "Onboarding pending", "Complete the onboarding before continuing.");
    }

    public static ApiException emailNotWhitelisted(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.EMAIL_NOT_WHITELISTED,
                "E-mail not whitelisted", detail);
    }

    public static ApiException accessDenied() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorTypes.ACCESS_DENIED,
                "Access denied", "You do not have permission for this operation.");
    }

    public static ApiException lastAdmin() {
        return new ApiException(HttpStatus.CONFLICT, ErrorTypes.LAST_ADMIN,
                "Last ADMIN", "The platform cannot be left without an active ADMIN.");
    }

    public static ApiException duplicateEmail() {
        return new ApiException(HttpStatus.CONFLICT, ErrorTypes.DUPLICATE_EMAIL,
                "Duplicate e-mail", "An active account with that e-mail already exists.");
    }

    public static ApiException tooManyAttempts(Duration retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorTypes.TOO_MANY_ATTEMPTS,
                "Too many attempts", "Attempt limit exceeded. Try again later.")
                .with("retryAfterSeconds", retryAfter.toSeconds());
    }

    public HttpStatus getStatus() { return status; }
    public URI getType() { return type; }
    public String getTitle() { return title; }
    public Map<String, Object> getExtras() { return extras; }
}
