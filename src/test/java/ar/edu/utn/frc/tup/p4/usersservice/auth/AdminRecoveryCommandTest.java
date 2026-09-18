package ar.edu.utn.frc.tup.p4.usersservice.auth;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.cli.AdminRecoveryCommand;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-32 - RF-ROL-04, DoD criterion #29. */
class AdminRecoveryCommandTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET = "the-installation-secret";

    @DynamicPropertySource
    static void secretHash(DynamicPropertyRegistry registry) {
        String hash = new BCryptPasswordEncoder(4).encode(TEST_SECRET);
        registry.add("users.breakglass.secret-hash", () -> hash);
    }

    @Autowired AdminRecoveryCommand command;
    @Autowired UserRepository repo;

    @Test
    void correctSecretCreatesAnAdminWithForcedPasswordChange() {
        var id = command.recover(TEST_SECRET,
                "Recovery", "Admin", "recovery-" + java.util.UUID.randomUUID() + "@utn.edu.ar",
                "validpassword1");

        assertThat(repo.findById(id)).get().satisfies(u -> {
            assertThat(u.getRole()).isEqualTo(Role.ADMIN);
            assertThat(u.mustChangePassword()).isTrue();
        });
    }

    @Test
    void wrongSecretCreatesNothing() {
        long before = repo.count();
        assertThatThrownBy(() -> command.recover("wrong", "R", "A",
                "no-" + java.util.UUID.randomUUID() + "@utn.edu.ar", "validpassword1"))
                .isInstanceOf(SecurityException.class);
        assertThat(repo.count()).isEqualTo(before);
    }

    @Test
    void secretNeverAppearsInTheErrorMessage() {
        assertThatThrownBy(() -> command.recover("leakable-secret", "R", "A",
                "x-" + java.util.UUID.randomUUID() + "@utn.edu.ar", "validpassword1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageNotContaining("leakable-secret");
    }

    @Test
    @Disabled("waiting for L3 T6 AccountEventPublisher / T7 NotificationEventPublisher")
    void breakglassLeavesAdminRecoveryInTheOutbox() {
        // TODO: when L3 provides AccountEventPublisher, verify that
        // AdminRecoveryCommand writes an OutboxEvent with topic "auditoria"
        // and a payload containing "RECUPERACION_ADMIN" INSIDE tx.execute.
        // OutboxRepository belongs to L1 and already exists.
    }

    @Test
    @Disabled("waiting for L3 T6 AccountEventPublisher / T7 NotificationEventPublisher")
    void breakglassEmailsEveryActiveAdmin() {
        // TODO: when L3 provides NotificationEventPublisher, verify that
        // AdminRecoveryCommand calls mails.send(EmailType.BREAKGLASS_ALERT, ...)
        // for every active ADMIN other than the newly created one.
        // EmailType and NotificationEventPublisher belong to L3.
    }
}
