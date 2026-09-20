package ar.edu.utn.frc.tup.p4.usersservice.config;

import ar.edu.utn.frc.tup.p4.usersservice.auth.services.SessionCookieService;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * The service's OpenAPI specification.
 *
 * <p><b>Why the scheme is a cookie rather than Bearer or the X-* headers.</b>
 * This service does NOT validate JWTs (DEC-08): it receives an identity already
 * resolved in {@code X-User-Id} / {@code X-User-Roles}, which the gateway
 * injects. Documenting those headers as the authentication mechanism would be
 * wrong for two reasons:
 *
 * <ol>
 *   <li>Readers open this page through nginx:3000, so "Try it out" goes through
 *       nginx -> gateway -> here. The gateway adds the X-* headers along the
 *       way; the client never sends them.</li>
 *   <li>Documenting X-* as client-supplied would document the vulnerability
 *       covered by non-negotiable 1: those headers are reserved and the gateway
 *       overwrites them. A copyable example that sends them manually would
 *       invite exactly what the design prohibits.</li>
 * </ol>
 *
 * <p>It is not a Bearer header either: the gateway ("Cookie Sessions" spec,
 * decision 3 of {@code PrivateRouteGuard}) requires a person principal to
 * arrive in the {@code fu_at} cookie and rejects the same token with 401 if it
 * travels in {@code Authorization}. Documenting Bearer would invite users to
 * paste the access token into Swagger UI's header and see a misleading 401.
 * Since this page is served from the same origin as the API, the browser sends
 * the cookie automatically after login; there is nothing to type.
 *
 * <p>No {@code servers} entry is declared: the specification is served from the
 * same origin as the API (nginx:3000), so Swagger UI resolves paths relatively
 * and "Try it out" works without configuration. An absolute URL here would
 * break whenever the compose port or host changes.
 */
@Configuration
public class OpenApiConfig {

    /** The name private controllers use to reference the scheme. */
    public static final String COOKIE_SCHEME = "cookieAuth";

    @Bean
    OpenAPI usersServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("users-service - Topic 01 - Identity and Users")
                        .version("v1")
                        .description("""
                                Owner of platform identity: registration, credentials, sessions,
                                roles and token issuance.

                                **Everything enters through the API gateway.** This service does
                                not publish ports and does not validate the JWT: it trusts the
                                `X-*` headers injected by the gateway after validating the
                                signature and session.

                                **Errors are always `application/problem+json`** and are
                                distinguished by the `type` field, never by status. All `type`
                                values live under `https://tpi.utn.frc/errors/`.

                                Routes under `/api/users/public/**` are anonymous; all others
                                require a current access token."""))
                .components(new Components().addSecuritySchemes(COOKIE_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(SessionCookieService.ACCESS_COOKIE)
                                .description("""
                                        The HttpOnly cookie set by `POST /api/users/public/auth/2fa/verify`
                                        (or `/refresh`). The GATEWAY validates it, not this service.

                                        It does NOT use the `Authorization` header: the gateway rejects
                                        a person token arriving there with 401 (decision 3, "Cookie
                                        Sessions" spec). Since this page is served from the same origin
                                        as the API, the browser sends the cookie automatically after you
                                        log in here; there is nothing to paste into "Authorize".

                                        Be aware of two known pitfalls when testing here: wait about
                                        4 seconds after login (the gateway caches session state for
                                        3 seconds), and the stack's first login may return 503 because
                                        BCrypt cost 12 is slow on a cold JVM.""")));
    }

    /** The name of the reusable error-body schema. */
    private static final String PROBLEM = "ProblemDetail";

    /**
     * Error responses shared by EVERY endpoint, added in one place.
     *
     * <p>The error contract is uniform across the service (non-negotiable 2):
     * same media type, same body, and clients branch on {@code type}. Repeating
     * four {@code @ApiResponse} annotations on each of about 28 methods would
     * copy a single decision 112 times, and one omission on a new endpoint would
     * make the documentation claim that it fails differently from the rest.
     *
     * <p>401/403 are added ONLY to private routes. Adding them everywhere would
     * be misleading in the most costly direction: it would suggest that a
     * public route can return 401, exactly what non-negotiable 3 prohibits.
     */
    @Bean
    OpenApiCustomizer errorResponses(@Value("${app.api.public-path}") String publicPath) {
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM, problemDetailSchema());

            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
                // Even a GET without a body can return 400: an {id} that cannot
                // be converted to UUID goes through the same @RestControllerAdvice.
                addIfMissing(op, "400", "Validation failed. `type`: `validation`.");

                if (isPrivate(path, publicPath)) {
                    addIfMissing(op, "401", "No valid identity. `type`: `not-authenticated`.");
                    addIfMissing(op, "403",
                            "Insufficient role, or an account blocked by a gate. `type`: "
                            + "`access-denied`, `pending-account`, `password-change-required` "
                            + "or `onboarding-pending`.");
                }
            }));
        };
    }

    /**
     * JWKS does not use the public prefix - it is a web convention (RFC 8615),
     * not a project convention - but it is still anonymous: SecurityConfig
     * allows it with permitAll. Without this exception it would be documented
     * as if it could return 401.
     */
    private boolean isPrivate(String path, String publicPath) {
        return !path.startsWith(publicPath) && !path.startsWith("/.well-known");
    }

    /**
     * Adds the status code ONLY if the endpoint has not documented it already.
     *
     * <p>This is not an optimization; it is correctness. Several endpoints have
     * a 400 that is NOT `validation` - {@code invalid-code} and
     * {@code invalid-link} are also 400 - and describing that distinction is
     * what makes the page useful. An unconditional {@code addApiResponse} would
     * overwrite them because the customizer runs AFTER springdoc reads the
     * annotations, and the documentation would say "validation failed" where
     * 400 actually means "the link expired".
     */
    private void addIfMissing(io.swagger.v3.oas.models.Operation op, String code, String description) {
        if (op.getResponses().get(code) == null) {
            op.getResponses().addApiResponse(code, problemResponse(description));
        }
    }

    private ApiResponse problemResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
    }

    private Schema<?> problemDetailSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.description("""
                RFC 7807. Clients branch on `type`, NEVER on status: two errors
                with the same 403 may require different screens. All `type`
                values live under `https://tpi.utn.frc/errors/`.""");
        schema.properties(Map.of(
                "type", new StringSchema()
                        .description("The error identity. This is the field clients branch on.")
                        .example("https://tpi.utn.frc/errors/invalid-credentials"),
                "title", new StringSchema().example("Invalid credentials"),
                "status", new Schema<Integer>().type("integer").format("int32").example(401),
                "detail", new StringSchema().example("The e-mail address or password does not match."),
                "instance", new StringSchema()
                        .description("The path of the failed request.")
                        .example("/api/users/public/auth/login")));
        return schema;
    }
}
