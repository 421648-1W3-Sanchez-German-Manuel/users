package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void twelve_characters_are_enough_with_no_composition_rules() {
        // NIST SP 800-63B: length, not complexity. "Password1!" is 10
        // predictable characters; 12 free ones carry more real entropy.
        assertThatCode(() -> PasswordPolicy.validate("alllowercase")).doesNotThrowAnyException();
    }

    @Test
    void fewer_than_twelve_characters_is_rejected() {
        assertThatThrownBy(() -> PasswordPolicy.validate("short123")).isInstanceOf(ApiException.class);
    }

    @Test
    void more_than_72_BYTES_is_rejected() {
        // BCrypt silently TRUNCATES at 72 bytes: without this limit a
        // 100-character password is validated against its first 72 and the
        // user believes in a security they do not have.
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void the_limit_is_measured_in_BYTES_not_characters() {
        // 40 accented characters are 80 bytes in UTF-8. Counting characters
        // this would pass, and BCrypt would cut in the middle of one.
        String fortyAccentedCharacters = "á".repeat(40);
        assertThatThrownBy(() -> PasswordPolicy.validate(fortyAccentedCharacters))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_common_password_is_rejected_even_when_long_enough() {
        assertThatThrownBy(() -> PasswordPolicy.validate("password12")).isInstanceOf(ApiException.class);
    }
}
