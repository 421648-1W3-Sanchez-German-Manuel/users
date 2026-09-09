package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

/**
 * DEC-08 - users-service does NOT validate the JWT. Its Authentication comes
 * EXCLUSIVELY from the X-* headers injected by the gateway.
 *
 * What makes those headers trustworthy is that this service's port is NOT
 * published outside the private network (U11 / DEC-40): nobody can reach it
 * without going through the gateway, and the gateway strips incoming X-*
 * headers before injecting its own. Publish the port and this filter turns
 * into a complete security hole.
 */
@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String tipo = req.getHeader(IdentityHeaders.PRINCIPAL_TYPE);

        if ("user".equals(tipo)) {
            autenticar(new GatewayPrincipal("user",
                            UUID.fromString(req.getHeader(IdentityHeaders.USER_ID)), null),
                    rolesFrom(req.getHeader(IdentityHeaders.USER_ROLES)));
        } else if ("service".equals(tipo)) {
            autenticar(new GatewayPrincipal("service", null,
                            req.getHeader(IdentityHeaders.SERVICE_ID)),
                    scopesFrom(req.getHeader(IdentityHeaders.SERVICE_SCOPES)));
        }
        // Third case: no headers -> public route. It does not authenticate and does not fail.

        chain.doFilter(req, res);
    }

    private void autenticar(GatewayPrincipal principal, List<GrantedAuthority> authorities) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Person roles: all prefixed with ROLE_ so that hasRole(...) matches. */
    private List<GrantedAuthority> rolesFrom(String header) {
        return parts(header).stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }

    /**
     * DEC-05: the header reads "MS,users.profile.read". MS is a ROLE -> ROLE_MS.
     * Scopes are bare AUTHORITIES -> hasAuthority('users.profile.read').
     */
    private List<GrantedAuthority> scopesFrom(String header) {
        return parts(header).stream()
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("MS".equals(s) ? "ROLE_MS" : s))
                .toList();
    }

    private List<String> parts(String header) {
        if (header == null || header.isBlank()) return List.of();
        return Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
