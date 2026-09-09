package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Request tracing, on the microservice side.
 *
 * Without this, following a request end to end is impossible: the gateway
 * writes its line carrying the `requestId` and the service writes none, so
 * there are no two ends to join. This filter puts the `requestId` and the
 * `traceId` that arrive as headers into the MDC, and writes ONE line per
 * request served.
 *
 * It is the same piece EVERY service behind the gateway needs: correlation
 * only exists if every service writes it the same way. It is a contract for
 * all topics, not a decision of this one.
 *
 * It runs first (@Order HIGHEST_PRECEDENCE) so that the lines from the security
 * chain also carry the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_TRACE_ID = "traceId";

    /**
     * The header is chosen by the caller and ends up in a log file: a value
     * with line breaks manufactures fake log lines. Only something that can be
     * an identifier gets through. The gateway already validates it; the check
     * is repeated because this filter cannot assume the gateway is always on
     * the other side.
     */
    private static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        long inicio = System.nanoTime();
        putInMdc(MDC_REQUEST_ID, req.getHeader("X-Request-Id"));
        putInMdc(MDC_TRACE_ID, traceIdFrom(req.getHeader("traceparent")));
        try {
            chain.doFilter(req, res);
        } finally {
            // Never log the body or the Authorization header: a token in a log
            // file is a stolen token, it just takes someone reading logs.
            log.info("{} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(),
                    res.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private void putInMdc(String key, String valor) {
        if (valor != null && ID_VALIDO.matcher(valor).matches()) MDC.put(key, valor);
    }

    /** W3C Trace Context: `00-{traceId 32 hex}-{spanId 16 hex}-{flags}`. */
    private String traceIdFrom(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.split("-");
        return parts.length >= 3 && parts[1].matches("[0-9a-f]{32}") ? parts[1] : null;
    }
}
