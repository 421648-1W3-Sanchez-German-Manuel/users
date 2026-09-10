package ar.edu.utn.frc.tup.p4.usersservice.shared.gates;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.EnumSet;
import java.util.Set;

import static ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate.Gate.*;

/**
 * DEC-14 - the three gates of §8, in order, each with its own error type.
 * Solo aplican a X-Principal-Type: user.
 *
 * DEC-23: these are the FINE gates, over users-service routes. The coarse gate
 * over other services' routes is applied by the gateway, reading the est/pwd/onb
 * claims - this service never sees that traffic.
 */
@Component
public class AccountGateInterceptor implements HandlerInterceptor {

    private final UserRepository repo;

    public AccountGateInterceptor(UserRepository repo) { this.repo = repo; }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof GatewayPrincipal p) || !p.isPerson()) {
            return true;   // publico o token de servicio: ningun gate aplica
        }

        Set<SkipAccountGate.Gate> exentos = exencionesDe(hm);
        if (exentos.containsAll(EnumSet.allOf(SkipAccountGate.Gate.class))) return true;

        // One query per authenticated person request. The status does NOT come
        // the headers: the source of truth is the row, not the token (DEC-23).
        User u = repo.findByIdAndDeletedAtIsNull(p.id())
                .orElseThrow(ApiException::invalidCredentials);

        if (!exentos.contains(ESTADO) && u.getAccountStatus() != AccountStatus.ACTIVE) {
            throw ApiException.pendingAccount(u.getAccountStatus());
        }
        if (!exentos.contains(PASSWORD) && u.mustChangePassword()) {
            throw ApiException.passwordChangeRequired();
        }
        if (!exentos.contains(ONBOARDING) && u.isFirstLogin()) {
            throw ApiException.onboardingPending();
        }
        return true;
    }

    private Set<SkipAccountGate.Gate> exencionesDe(HandlerMethod hm) {
        SkipAccountGate a = hm.getMethodAnnotation(SkipAccountGate.class);
        return a == null ? EnumSet.noneOf(SkipAccountGate.Gate.class)
                         : EnumSet.copyOf(java.util.List.of(a.value()));
    }
}
