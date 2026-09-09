package ar.edu.utn.frc.tup.p4.usersservice.shared.notifications;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTypeTest extends AbstractIntegrationTest {

    @Autowired EmailTemplateService templates;

    /** Filler variables covering every template. */
    private Map<String, Object> vars() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("firstNames", "Ana");
        variables.put("code", "123456");
        variables.put("enlace", "https://app.tpi.utn.frc/reset?token=x");
        variables.put("accountStatus", "PENDING_COURSE");
        variables.put("reason", "un motivo");
        variables.put("emailSolicitado", "otro@utn.edu.ar");
        variables.put("resultado", "APPROVED");
        variables.put("adminId", "a3f1c2e4");
        return variables;
    }

    @ParameterizedTest
    @EnumSource(EmailType.class)
    void eachTemplateExistsOnTheClasspath(EmailType type) {
        assertThat(new ClassPathResource("templates/" + type.plantilla()).exists())
                .as("missing templates/%s for %s", type.plantilla(), type)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(EmailType.class)
    void eachTemplateRendersWithoutUnresolvedVariables(EmailType type) {
        var mail = templates.render(type, vars());

        assertThat(mail.asunto()).isNotBlank();
        assertThat(mail.html()).isNotBlank();
        assertThat(mail.html()).doesNotContain("${").doesNotContain("[[");
    }

    @ParameterizedTest
    @EnumSource(EmailType.class)
    void eachTypeHasAUniqueEventType(EmailType type) {
        long duplicates = java.util.Arrays.stream(EmailType.values())
                .filter(candidate -> candidate.eventType().equals(type.eventType()))
                .count();
        assertThat(duplicates).isEqualTo(1);
    }
}
