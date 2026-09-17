package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ErrorTypes;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountGateInterceptorTest {

    UserRepository repo = mock(UserRepository.class);
    AccountGateInterceptor interceptor = new AccountGateInterceptor(repo);
    UUID userId = UUID.randomUUID();

    static class Handlers {
        public void protectedEndpoint() { }
        @SkipAccountGate({SkipAccountGate.Gate.ACCOUNT_STATUS, SkipAccountGate.Gate.PASSWORD,
                          SkipAccountGate.Gate.ONBOARDING})
        public void me() { }
        /** The same exemptions as POST /api/users/auth/password/change. */
        @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
        public void passwordChange() { }
        /** The same exemptions as PATCH /api/users/me/onboarding. */
        @SkipAccountGate({SkipAccountGate.Gate.ONBOARDING, SkipAccountGate.Gate.PASSWORD})
        public void onboarding() { }
    }

    private HandlerMethod handler(String methodName) throws Exception {
        Method m = Handlers.class.getMethod(methodName);
        return new HandlerMethod(new Handlers(), m);
    }

    private void authenticatePerson() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new GatewayPrincipal("user", userId, null), null, List.of()));
    }

    private User user(AccountStatus status, boolean mustChangePassword, boolean firstLogin) {
        User u = User.create("Ana", "P", "a@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(status);
        if (mustChangePassword) u.requirePasswordChange();
        if (!firstLogin) u.completeOnboarding("ana", null, true);
        return u;
    }

    @BeforeEach
    void setUp() { authenticatePerson(); }

    @AfterEach
    void cleanUp() { SecurityContextHolder.clearContext(); }

    @Test
    void a_pending_account_blocks_a_protected_endpoint() throws Exception {
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(user(AccountStatus.PENDING_COURSE, false, false)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protectedEndpoint")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getExtras())
                        .containsEntry("accountStatus", "PENDING_COURSE"));
    }

    @Test
    void GET_me_passes_through_all_three_gates() throws Exception {
        // This is how the frontend finds out WHAT the account is missing
        // for the person. If it were also blocked, there would be no way to know.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(user(AccountStatus.PENDING_COURSE, true, true)));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("me"))).isTrue();
    }

    @Test
    void pending_onboarding_blocks_even_when_the_account_is_ACTIVE() throws Exception {
        // DoD criterion #7.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(user(AccountStatus.ACTIVE, false, true)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protectedEndpoint")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("onboarding");
    }

    @Test
    void both_exit_endpoints_work_with_both_gates_pending() throws Exception {
        // RF-USR-01. The initial ADMIN starts with mustChangePassword AND firstLogin
        // set to true. If each endpoint exempted only ONE gate, onboarding would
        // block the password endpoint and password would block onboarding, leaving
        // the account locked and the requirement impossible to satisfy.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(user(AccountStatus.ACTIVE, true, true)));

        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("passwordChange"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("onboarding"))).isTrue();
    }

    @Test
    void both_exit_endpoints_still_respect_the_account_status_gate() throws Exception {
        // What is NOT relaxed: an account that is not ACTIVE completes no
        // onboarding and changes no password. They only unblock each other.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(user(AccountStatus.PENDING_COURSE, true, true)));

        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("onboarding")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getType()).isEqualTo(ErrorTypes.PENDING_ACCOUNT);
                    assertThat(api.getExtras()).containsEntry("accountStatus", "PENDING_COURSE");
                });
    }

    @Test
    void a_service_token_does_not_pass_through_any_gate() throws Exception {
        // An MS does not stand for a person with an account: no status to check.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new GatewayPrincipal("service", null, "courses-service"), null, List.of()));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protectedEndpoint"))).isTrue();
    }

    @Test
    void gate_order_is_account_status_password_onboarding() throws Exception {
        // With all three active, STATUS wins: it is the most restrictive and the
        // one the frontend has to resolve first.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(user(AccountStatus.PENDING_EMAIL, true, true)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protectedEndpoint")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getType()).isEqualTo(ErrorTypes.PENDING_ACCOUNT);
                    assertThat(api.getExtras()).containsEntry("accountStatus", "PENDING_EMAIL");
                });
    }
}
