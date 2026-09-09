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

    private UUID activo() {
        User u = User.create("Ana", "P", "onb-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void el_onboarding_SIN_avatarRef_cierra_el_gate_3() {
        UUID id = activo();
        assertThat(users.me(id).firstLogin()).isTrue();

        users.completeOnboarding(id, "anaperez", null, true);

        var me = users.me(id);
        assertThat(me.firstLogin()).isFalse();
        assertThat(me.avatarRef()).isNull();
        assertThat(me.guidedTourCompleted()).isTrue();
    }

    @Test
    void si_viene_avatarRef_se_guarda() {
        UUID id = activo();
        users.completeOnboarding(id, "anaperez", "avatars/ana.png", true);
        assertThat(users.me(id).avatarRef()).isEqualTo("avatars/ana.png");
    }

    @Test
    void GET_me_devuelve_los_cuatro_flags_que_el_frontend_necesita() {
        var me = users.me(activo());
        assertThat(me.accountStatus()).isNotNull();
        assertThat(me.mustChangePassword()).isFalse();
        assertThat(me.firstLogin()).isTrue();
        assertThat(me.guidedTourCompleted()).isFalse();
    }
}
