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
        assertThatCode(() -> PasswordPolicy.validate("todaminusculas")).doesNotThrowAnyException();
    }

    @Test
    void fewer_than_twelve_characters_is_rejected() {
        assertThatThrownBy(() -> PasswordPolicy.validate("corta123")).isInstanceOf(ApiException.class);
    }

    @Test
    void mas_de_72_BYTES_se_rechaza() {
        // BCrypt silently TRUNCATES at 72 bytes: without this limit a
        // 100-character password is validated against its first 72 and the
        // user believes in a security they do not have.
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void el_limite_se_mide_en_BYTES_no_en_caracteres() {
        // 40 accented characters are 80 bytes in UTF-8. Counting characters
        // this would pass, and BCrypt would cut in the middle of one.
        String cuarentaAcentos = "á".repeat(40);
        assertThatThrownBy(() -> PasswordPolicy.validate(cuarentaAcentos))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void a_common_password_is_rejected_even_when_long_enough() {
        assertThatThrownBy(() -> PasswordPolicy.validate("password12")).isInstanceOf(ApiException.class);
    }
}