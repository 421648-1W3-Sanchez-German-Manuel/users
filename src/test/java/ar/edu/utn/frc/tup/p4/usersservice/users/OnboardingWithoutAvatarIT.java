package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-GL-05 escape + DEC-GL-11: with GitHub disabled (default), the tour alone
 * closes gate 3. avatarRef is no longer part of the onboarding body (DEC-GL-21).
 */
class OnboardingWithoutAvatarIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;

    private UUID activeUserId() {
        User u = User.create("Ana", "P", "onb-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void onboarding_tour_alone_closes_gate_3_when_github_is_disabled() {
        UUID id = activeUserId();
        assertThat(users.me(id).firstLogin()).isTrue();

        users.completeOnboarding(id, true);

        var me = users.me(id);
        assertThat(me.firstLogin()).isFalse();
        assertThat(me.avatarRef()).isNull();
        assertThat(me.guidedTourCompleted()).isTrue();
        assertThat(me.githubUsername()).isNull();
    }

    @Test
    void GET_me_returns_the_four_flags_needed_by_the_frontend() {
        var me = users.me(activeUserId());
        assertThat(me.accountStatus()).isNotNull();
        assertThat(me.mustChangePassword()).isFalse();
        assertThat(me.firstLogin()).isTrue();
        assertThat(me.guidedTourCompleted()).isFalse();
    }
}
