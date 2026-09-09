package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GatewayIdentityFilterTest {

    private final GatewayIdentityFilter filter = new GatewayIdentityFilter();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private Authentication run(MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void a_person_with_comma_separated_roles_and_no_spaces() throws Exception {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaders.PRINCIPAL_TYPE, "user");
        req.addHeader(IdentityHeaders.USER_ID, id.toString());
        req.addHeader(IdentityHeaders.USER_ROLES, "STUDENT,PROFESSOR");

        Authentication auth = run(req);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_PROFESSOR");
        assertThat(((GatewayPrincipal) auth.getPrincipal()).id()).isEqualTo(id);
        assertThat(((GatewayPrincipal) auth.getPrincipal()).isPerson()).isTrue();
    }

    @Test
    void servicio_con_MS_dentro_de_scopes_mapea_a_ROLE_MS_y_el_resto_a_authorities() throws Exception {
        // DEC-05: "MS,users.profile.read". MS goes in as ROLE_ so that
        // hasRole('MS') matches; scopes go in as bare authorities so that
        // hasAuthority('users.profile.read') matches.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaders.PRINCIPAL_TYPE, "service");
        req.addHeader(IdentityHeaders.SERVICE_ID, "cursos-service");
        req.addHeader(IdentityHeaders.SERVICE_SCOPES, "MS,users.profile.read");

        Authentication auth = run(req);

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_MS", "users.profile.read");
        assertThat(((GatewayPrincipal) auth.getPrincipal()).serviceId()).isEqualTo("cursos-service");
        assertThat(((GatewayPrincipal) auth.getPrincipal()).isPerson()).isFalse();
    }

    @Test
    void sin_headers_no_hay_Authentication() throws Exception {
        // Third case: a public route. It is not an error; it simply does not authenticate.
        assertThat(run(new MockHttpServletRequest())).isNull();
    }

    @Test
    void headers_with_spaces_are_parsed_all_the_same() throws Exception {
        // Defensive: the contract says comma with no space, but one extra space
        // must not silently break authorization.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(IdentityHeaders.PRINCIPAL_TYPE, "user");
        req.addHeader(IdentityHeaders.USER_ID, UUID.randomUUID().toString());
        req.addHeader(IdentityHeaders.USER_ROLES, "STUDENT, PROFESSOR");

        assertThat(run(req).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_PROFESSOR");
    }
}
