package ar.edu.utn.frc.tup.p4.usersservice.shared.web;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void invalid_credentials_does_not_reveal_the_cause() {
        // Anti-enumeration: ONE message for "no such e-mail" and for "wrong
        // password". If they differed, an attacker could tell which e-mails are
        // registered just by reading the error.
        assertThat(ApiException.invalidCredentials().getMessage())
                .doesNotContainIgnoringCase("does not exist", "not found", "unknown", "no such");
    }

    @Test
    void invalid_code_does_not_tell_expired_from_wrong() {
        assertThat(ApiException.invalidCode().getMessage())
                .isEqualTo("The code is wrong or has expired.");
    }

    @Test
    void pending_account_carries_the_status_in_the_body() {
        // The frontend needs it to decide which screen to show.
        assertThat(ApiException.pendingAccount(AccountStatus.PENDING_COURSE).getExtras())
                .containsEntry("accountStatus", "PENDING_COURSE");
    }

    @Test
    void too_many_attempts_uses_the_type_shared_with_the_gateway() {
        // DEC-24: the frontend has a single handling branch.
        ApiException ex = ApiException.tooManyAttempts(Duration.ofMinutes(15));
        assertThat(ex.getType().toString()).endsWith("/too-many-attempts");
        assertThat(ex.getStatus().value()).isEqualTo(429);
        assertThat(ex.getExtras()).containsEntry("retryAfterSeconds", 900L);
    }
}
