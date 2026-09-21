package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.config.RegistrationProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ErrorTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstitutionalEmailPolicyTest {

    private static InstitutionalEmailPolicy policy(String... domains) {
        return new InstitutionalEmailPolicy(new RegistrationProperties(List.of(domains)));
    }

    @Test
    void accepts_an_address_on_a_configured_domain() {
        assertThat(policy("frc.utn.edu.ar").isAllowed("ana@frc.utn.edu.ar")).isTrue();
    }

    @Test
    void domain_match_is_case_insensitive_and_ignores_config_surroundings() {
        assertThat(policy("frc.utn.edu.ar").isAllowed("ana@FRC.UTN.EDU.AR")).isTrue();
        assertThat(policy(" frc.utn.edu.ar ").isAllowed("ana@frc.utn.edu.ar")).isTrue();
        assertThat(policy("@frc.utn.edu.ar").isAllowed("ana@frc.utn.edu.ar")).isTrue();
    }

    @Test
    void accepts_any_of_the_configured_domains() {
        assertThat(policy("frc.utn.edu.ar", "utn.edu.ar").isAllowed("ana@utn.edu.ar")).isTrue();
    }

    @Test
    void rejects_a_foreign_domain() {
        assertThat(policy("frc.utn.edu.ar").isAllowed("ana@gmail.com")).isFalse();
    }

    @Test
    void rejects_an_address_with_no_usable_domain() {
        assertThat(policy("frc.utn.edu.ar").isAllowed("ana@")).isFalse();
        assertThat(policy("frc.utn.edu.ar").isAllowed("ana")).isFalse();
        assertThat(policy("frc.utn.edu.ar").isAllowed(null)).isFalse();
    }

    @Test
    void validate_throws_email_not_whitelisted_for_a_foreign_domain() {
        assertThatThrownBy(() -> policy("frc.utn.edu.ar").validate("ana@gmail.com"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("type", ErrorTypes.EMAIL_NOT_WHITELISTED);
    }
}
