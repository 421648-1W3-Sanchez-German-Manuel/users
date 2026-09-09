package ar.edu.utn.frc.tup.p4.usersservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class UsersServiceApplicationTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void the_context_starts() {
        assertThat(ctx).isNotNull();
    }

    /**
     * DEC-08: we do not validate JWTs. If somebody adds the resource server
     * starter, a JwtDecoder shows up in the context and this test catches it.
     */
    @Test
    void no_existe_ningun_JwtDecoder_en_el_contexto() {
        assertThat(ctx.getBeanNamesForType(org.springframework.security.oauth2.jwt.JwtDecoder.class))
                .isEmpty();
    }
}
