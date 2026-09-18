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
 * DEC-30 - the test that keeps EVERY new user from being locked in a 403.
 * Without object storage there is no upload endpoint; if avatarRef were
 * mandatory, the ONBOARDING gate could never be closed.
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
    void onboarding_WITHOUT_avatarRef_closes_gate_3() {
        UUID id = activeUserId();
        assertThat(users.me(id).firstLogin()).isTrue();

        users.completeOnboarding(id, "anaperez", null, true);

        var me = users.me(id);
        assertThat(me.firstLogin()).isFalse();
        assertThat(me.avatarRef()).isNull();
        assertThat(me.guidedTourCompleted()).isTrue();
    }

    @Test
    void avatarRef_is_stored_when_provided() {
        UUID id = activeUserId();
        users.completeOnboarding(id, "anaperez", "avatars/ana.png", true);
        assertThat(users.me(id).avatarRef()).isEqualTo("avatars/ana.png");
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
