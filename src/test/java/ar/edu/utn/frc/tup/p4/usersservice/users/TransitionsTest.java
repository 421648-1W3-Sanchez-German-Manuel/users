package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionsTest {

    private User withStatus(AccountStatus status, Role role) {
        User u = User.create("Ana", "Perez", "ana@utn.edu.ar", "$2a$12$hash", role, "v1");
        u.forceStatusForTest(status);
        return u;
    }

    @Test
    void activating_a_student_moves_it_to_pending_course() {
        User u = withStatus(AccountStatus.PENDING_EMAIL, Role.STUDENT);
        u.activate();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.PENDING_COURSE);
        assertThat(u.isEmailVerified()).isTrue();
    }

    @Test
    void activating_a_professor_goes_straight_to_active() {
        User u = withStatus(AccountStatus.PENDING_EMAIL, Role.PROFESSOR);
        u.activate();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void course_validation_on_an_already_active_account_is_a_no_op() {
        // Consumer idempotency requirement (DEC-13): reprocessing is not an error.
        User u = withStatus(AccountStatus.ACTIVE, Role.STUDENT);
        u.activateAfterCourseValidation();
        assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void every_status_can_be_deactivated_except_DEACTIVATED(AccountStatus from) {
        User u = withStatus(from, Role.STUDENT);
        if (from == AccountStatus.DEACTIVATED) {
            assertThatThrownBy(u::deactivate).isInstanceOf(InvalidTransitionException.class);
        } else {
            u.deactivate();
            assertThat(u.getAccountStatus()).isEqualTo(AccountStatus.DEACTIVATED);
            assertThat(u.getDeletedAt()).isNotNull();
        }
    }

    @Test
    void DEACTIVATED_is_terminal_and_cannot_transition_to_anything() {
        // This is what DEC-21 rests on: someone coming back needs a new row,
        // which is why the e-mail has to be reusable.
        User u = withStatus(AccountStatus.DEACTIVATED, Role.STUDENT);
        assertThatThrownBy(u::activate).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(u::activateAfterCourseValidation).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(u::deactivate).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void activating_from_ACTIVE_is_an_invalid_transition() {
        User u = withStatus(AccountStatus.ACTIVE, Role.STUDENT);
        assertThatThrownBy(u::activate).isInstanceOf(InvalidTransitionException.class);
    }
}
