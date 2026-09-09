package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import ar.edu.utn.frc.tup.p4.usersservice.users.InvalidTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.stream.Collectors;

/** One single place. Never try/catch scattered across the controllers. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException ex, HttpServletRequest req) {
        ProblemDetail pd = base(ex.getStatus().value(), ex.getType(), ex.getTitle(), ex.getMessage(), req);
        ex.getExtras().forEach(pd::setProperty);

        HttpHeaders headers = new HttpHeaders();
        Object retry = ex.getExtras().get("retryAfterSeconds");
        if (retry != null) headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(retry));

        return ResponseEntity.status(ex.getStatus()).headers(headers).body(pd);
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail transicion(InvalidTransitionException ex, HttpServletRequest req) {
        return base(409, ErrorTypes.INVALID_TRANSITION, "Invalid transition", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return base(400, ErrorTypes.VALIDATION, "Invalid request", detail, req);
    }

    /**
     * A route that does not exist must answer 404, not 401. Without this,
     * Spring forwards to /error, the GatewayIdentityFilter does not run on the
     * ERROR dispatch (OncePerRequestFilter skips it), the SecurityContext
     * arrives empty and the entry point answers 401 with instance "/error".
     * Since the contract says a 401 sends the user to the login screen, a typo
     * in a frontend URL would log people out.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ProblemDetail noExiste(Exception ex, HttpServletRequest req) {
        return base(404, ErrorTypes.ROUTE_NOT_FOUND, "Route not found",
                "The requested route does not exist.", req);
    }

    /** The route exists but not with that verb. To a client it is the same family. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail metodo(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return base(405, ErrorTypes.ROUTE_NOT_FOUND, "Metodo no soportado",
                "Method " + ex.getMethod() + " is not supported on that route.", req);
    }

    /** An {id} that is not a UUID, an enum value that does not exist. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail tipo(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return base(400, ErrorTypes.VALIDATION, "Invalid request",
                "Parameter '" + ex.getName() + "' does not have the expected format.", req);
    }

    /**
     * Unreadable body. The parser's message is NOT exposed: it names internal
     * classes and fields, and it is unreadable for whoever has to fix it.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpo(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return base(400, ErrorTypes.VALIDATION, "Invalid request",
                "The request body is not valid JSON.", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridad(DataIntegrityViolationException ex, HttpServletRequest req) {
        // The database message is not exposed: it can leak index and column names.
        log.warn("Integrity violation on {}", req.getRequestURI(), ex);
        return base(409, ErrorTypes.DUPLICATE_EMAIL, "Conflicto",
                "The operation violates a uniqueness constraint.", req);
    }

    private ProblemDetail base(int status, URI type, String title, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(status), detail);
        pd.setType(type);
        pd.setTitle(title);
        pd.setInstance(URI.create(req.getRequestURI()));
        String requestId = req.getHeader("X-Request-Id");
        if (requestId != null) pd.setProperty("requestId", requestId);
        return pd;
    }
}
