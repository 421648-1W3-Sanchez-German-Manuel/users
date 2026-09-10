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
        public void protegido() { }
        @SkipAccountGate({SkipAccountGate.Gate.ESTADO, SkipAccountGate.Gate.PASSWORD,
                          SkipAccountGate.Gate.ONBOARDING})
        public void me() { }
        /** The same exemptions as POST /api/users/auth/password/change. */
        @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
        public void cambioPassword() { }
        /** The same exemptions as PATCH /api/users/me/onboarding. */
        @SkipAccountGate({SkipAccountGate.Gate.ONBOARDING, SkipAccountGate.Gate.PASSWORD})
        public void onboarding() { }
    }

    private HandlerMethod handler(String nombre) throws Exception {
        Method m = Handlers.class.getMethod(nombre);
        return new HandlerMethod(new Handlers(), m);
    }

    private void autenticarPersona() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new GatewayPrincipal("user", userId, null), null, List.of()));
    }

    private User usuario(AccountStatus status, boolean debeCambiar, boolean firstLogin) {
        User u = User.create("Ana", "P", "a@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        u.forceStatusForTest(status);
        if (debeCambiar) u.requirePasswordChange();
        if (!firstLogin) u.completeOnboarding("ana", null, true);
        return u;
    }

    @BeforeEach
    void setUp() { autenticarPersona(); }

    @AfterEach
    void limpiar() { SecurityContextHolder.clearContext(); }

    @Test
    void cuenta_pendiente_bloquea_una_ruta_protegida() throws Exception {
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_COURSE, false, false)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getExtras())
                        .containsEntry("accountStatus", "PENDING_COURSE"));
    }

    @Test
    void GET_me_atraviesa_los_tres_gates() throws Exception {
        // This is how the frontend finds out WHAT the account is missing
        // persona. Si tambien estuviera bloqueada, no habria forma de saberlo.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_COURSE, true, true)));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("me"))).isTrue();
    }

    @Test
    void onboarding_pendiente_bloquea_aunque_la_cuenta_este_ACTIVA() throws Exception {
        // Criterio de DoD #7.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.ACTIVE, false, true)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("onboarding");
    }

    @Test
    void las_dos_salidas_funcionan_con_los_dos_gates_pendientes() throws Exception {
        // RF-USR-01. El ADMIN inicial nace con mustChangePassword Y firstLogin
        // set to true. If each endpoint exempted only ONE gate, the password one
        // would be cut by onboarding and the onboarding one by password: the account
        // queda encerrada y el requisito es inalcanzable.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.ACTIVE, true, true)));

        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("cambioPassword"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("onboarding"))).isTrue();
    }

    @Test
    void las_dos_salidas_siguen_respetando_el_gate_de_estado() throws Exception {
        // What is NOT relaxed: an account that is not ACTIVE completes no
        // onboarding and changes no password. They only unblock each other.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_COURSE, true, true)));

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
    void un_token_de_servicio_no_atraviesa_ningun_gate() throws Exception {
        // An MS does not stand for a person with an account: no status to check.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new GatewayPrincipal("service", null, "cursos-service"), null, List.of()));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido"))).isTrue();
    }

    @Test
    void el_orden_es_estado_password_onboarding() throws Exception {
        // With all three active, STATUS wins: it is the most restrictive and the
        // one the frontend has to resolve first.
        when(repo.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(usuario(AccountStatus.PENDING_EMAIL, true, true)));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler("protegido")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getType()).isEqualTo(ErrorTypes.PENDING_ACCOUNT);
                    assertThat(api.getExtras()).containsEntry("accountStatus", "PENDING_EMAIL");
                });
    }
}
