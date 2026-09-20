package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.config.RegistrationProperties;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * SPEC §16.1 - self-registration is restricted to the institutional domain(s).
 *
 * The rule lives here, apart from {@link ar.edu.utn.frc.tup.p4.usersservice.users.services.RegistrationService},
 * on purpose: today only the student path applies it. Extending it to PROFESSOR
 * or GESTOR is a single {@code validate(email)} call in the corresponding
 * {@code register*} method, with no change to this class.
 *
 * The allowed domains come from configuration
 * ({@code users.registration.allowed-domains}); nothing here is hardcoded.
 */
@Component
public class InstitutionalEmailPolicy {

    private final List<String> allowedDomains;

    public InstitutionalEmailPolicy(RegistrationProperties properties) {
        this.allowedDomains = normalize(properties.allowedDomains());
    }

    /**
     * 403 {@code email-not-whitelisted}, the same {@code type} SPEC §17.2
     * catalogues for a non-institutional domain. Reusing it keeps the frontend's
     * error handling to a single branch.
     */
    public void validate(String email) {
        if (!isAllowed(email)) {
            throw ApiException.emailNotWhitelisted(
                    "Registration accepts only institutional e-mails ("
                    + String.join(", ", allowedDomains) + ").");
        }
    }

    public boolean isAllowed(String email) {
        String domain = domainOf(email);
        return domain != null && allowedDomains.contains(domain);
    }

    /** The lowercased part after the last {@code @}, or null when there is none. */
    private static String domainOf(String email) {
        if (email == null) return null;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return null;
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return domain.isEmpty() ? null : domain;
    }

    /** Normalized so " FRC.UTN.EDU.AR " and "@frc.utn.edu.ar" match the same way. */
    private static List<String> normalize(List<String> domains) {
        if (domains == null) return List.of();
        return domains.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .toList();
    }
}
