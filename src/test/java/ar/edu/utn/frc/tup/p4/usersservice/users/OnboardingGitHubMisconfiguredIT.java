package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flag on without credentials: no adapter registers, so nobody could ever
 * link. Requiring a link anyway would leave first_login set for every account
 * forever, and AccountGateInterceptor would then block the whole service with
 * no way out short of editing the database. It degrades to "off" instead.
 */
class OnboardingGitHubMisconfiguredIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void enabledWithoutCredentials(DynamicPropertyRegistry registry) {
        registry.add("users.git-providers.github.enabled", () -> "true");
        registry.add("users.git-providers.github.client-id", () -> "");
    }

    @Autowired UserService users;
    @Autowired UserRepository repo;

    @Test
    void enabled_without_credentials_does_not_lock_the_account_gate() {
        User u = User.create("Ana", "P", "onb-misconf-" + UUID.randomUUID() + "@utn.edu.ar",
                "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        repo.saveAndFlush(u);

        users.completeOnboarding(u.getId(), true);

        assertThat(users.me(u.getId()).firstLogin()).isFalse();
    }
}
