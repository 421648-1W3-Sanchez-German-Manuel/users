package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ErrorTypes;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity          // enables @PreAuthorize (layer 1)
public class SecurityConfig {

    /** DEC-38: cost 12. */
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, GatewayIdentityFilter identityFilter) throws Exception {
        return http
                // No CSRF and no session: a stateless API behind the gateway.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .addFilterBefore(identityFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/users/public/**").permitAll()
                        .requestMatchers("/.well-known/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        // The dispatch to /error runs WITHOUT the GatewayIdentityFilter
                        // (OncePerRequestFilter skips the ERROR dispatch): if it
                        // required authentication, any internal error would come
                        // out as a 401 and the frontend would send the user to
                        // the login screen because of a 404.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // Without this Spring falls back to Http403ForbiddenEntryPoint and a
                // request WITHOUT identity gets a 403 rather than a 401 — which
                // is what DoD criterion #16 asks for. They mean different things
                // to a client: 403 is "we know who you are and you cannot", 401
                // is "we do not know who you are". And the body has to be the
                // uniform ProblemDetail, not the default error page.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint())
                        // And the 403 for the same reason: the AccessDeniedException raised
                        // by @PreAuthorize is handled by the ExceptionTranslationFilter,
                        // NOT by the @RestControllerAdvice, so without this the
                        // default Spring body comes out — a correct 403 with no
                        // `type`, on the most frequent error of all: the role
                        // one. The contract says `access-denied`.
                        .accessDeniedHandler(accessDenied()))
                // DEC-08: there is NO .oauth2ResourceServer(...). If somebody adds it,
                // UsersServiceApplicationTest fails on the JwtDecoder in the context.
                .build();
    }

    /** 403 with the uniform ProblemDetail. The same type the gateway uses. */
    private AccessDeniedHandler accessDenied() {
        return (request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"type\":\"" + ErrorTypes.ACCESS_DENIED + "\","
                    + "\"title\":\"Access denied\","
                    + "\"status\":403,"
                    + "\"detail\":\"You do not have permission for this operation.\","
                    + "\"instance\":\"" + request.getRequestURI() + "\"}");
        };
    }

    /** 401 with the uniform ProblemDetail, not Spring's default error page. */
    private AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"type\":\"" + ErrorTypes.NOT_AUTHENTICATED + "\","
                    + "\"title\":\"No autenticado\","
                    + "\"status\":401,"
                    + "\"detail\":\"El request no trae headers de identidad validados.\","
                    + "\"instance\":\"" + request.getRequestURI() + "\"}");
        };
    }
}
