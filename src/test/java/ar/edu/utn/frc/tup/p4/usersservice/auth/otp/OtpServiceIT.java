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
        assertThat(otp.generate("test:1", Duration.ofMinutes(5))).matches("\\d{6}");
    }

    @Test
    void correctCodeVerifiesAndIsConsumed() {
        String code = otp.generate("test:2", Duration.ofMinutes(5));
        otp.verify("test:2", code);

        assertThatThrownBy(() -> otp.verify("test:2", code))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void wrongCodeAndUnknownKeyReturnTheSameResponse() {
        String code = otp.generate("test:3", Duration.ofMinutes(5));
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        String wrongCodeMessage = capture(() -> otp.verify("test:3", wrongCode));
        String unknownKeyMessage = capture(() -> otp.verify("test:unknown", code));

        assertThat(wrongCodeMessage).isEqualTo(unknownKeyMessage);
    }

    @Test
    void fifthFailureInvalidatesTheCode() {
        String code = otp.generate("test:4", Duration.ofMinutes(30));
        String wrongCode = code.equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> otp.verify("test:4", wrongCode))
                    .isInstanceOf(ApiException.class);
        }

        assertThatThrownBy(() -> otp.verify("test:4", code))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void regeneratingOverwritesThePreviousCode() {
        String previousCode = otp.generate("test:5", Duration.ofMinutes(30));
        String newCode = otp.generate("test:5", Duration.ofMinutes(30));
        while (newCode.equals(previousCode)) {
            newCode = otp.generate("test:5", Duration.ofMinutes(30));
        }

        String currentCode = newCode;
        assertThatThrownBy(() -> otp.verify("test:5", previousCode))
                .isInstanceOf(ApiException.class);
        otp.verify("test:5", currentCode);
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
