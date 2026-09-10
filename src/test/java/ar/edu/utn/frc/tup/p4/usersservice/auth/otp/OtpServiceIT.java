package ar.edu.utn.frc.tup.p4.usersservice.auth.otp;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-33 - one OTP engine for two-factor authentication and email validation. */
class OtpServiceIT extends AbstractIntegrationTest {

    @Autowired OtpService otp;

    @Test
    void generatesSixDigits() {
        assertThat(otp.generar("test:1", Duration.ofMinutes(5))).matches("\\d{6}");
    }

    @Test
    void correctCodeVerifiesAndIsConsumed() {
        String code = otp.generar("test:2", Duration.ofMinutes(5));
        otp.verificar("test:2", code);

        assertThatThrownBy(() -> otp.verificar("test:2", code))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void wrongCodeAndUnknownKeyReturnTheSameResponse() {
        String code = otp.generar("test:3", Duration.ofMinutes(5));
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        String wrongCodeMessage = capture(() -> otp.verificar("test:3", wrongCode));
        String unknownKeyMessage = capture(() -> otp.verificar("test:unknown", code));

        assertThat(wrongCodeMessage).isEqualTo(unknownKeyMessage);
    }

    @Test
    void fifthFailureInvalidatesTheCode() {
        String code = otp.generar("test:4", Duration.ofMinutes(30));
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> otp.verificar("test:4", wrongCode))
                    .isInstanceOf(ApiException.class);
        }

        assertThatThrownBy(() -> otp.verificar("test:4", code))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void regeneratingOverwritesThePreviousCode() {
        String previousCode = otp.generar("test:5", Duration.ofMinutes(30));
        String newCode = otp.generar("test:5", Duration.ofMinutes(30));
        while (newCode.equals(previousCode)) {
            newCode = otp.generar("test:5", Duration.ofMinutes(30));
        }

        String currentCode = newCode;
        assertThatThrownBy(() -> otp.verificar("test:5", previousCode))
                .isInstanceOf(ApiException.class);
        otp.verificar("test:5", currentCode);
    }

    private String capture(Runnable action) {
        try {
            action.run();
            return "no-error";
        } catch (ApiException exception) {
            return exception.getMessage();
        }
    }
}
