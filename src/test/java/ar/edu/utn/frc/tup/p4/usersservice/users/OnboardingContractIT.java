package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.users.dto.OnboardingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Etapa 4 criterion 1: onboarding body no longer carries githubUsername / avatarRef. */
class OnboardingContractIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onboarding_request_only_has_tourOk() throws Exception {
        OnboardingRequest req = mapper.readValue("""
                {"tourOk":true,"githubUsername":"should-be-ignored","avatarRef":"x"}
                """, OnboardingRequest.class);
        assertThat(req.tourOk()).isTrue();
        assertThat(OnboardingRequest.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("tourOk");
    }
}
