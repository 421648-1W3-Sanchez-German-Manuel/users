package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.util.List;

/**
 * SPEC §16.1 - self-registration is restricted to the institutional domain(s).
 *
 * A launch parameter, never a constant: the deployment decides which domain is
 * "institutional", and listing more than one is a config change, not a code
 * change. Bound from {@code users.registration.allowed-domains}.
 */
@ConfigurationProperties(prefix = "users.registration")
public record RegistrationProperties(@Name("allowed-domains") List<String> allowedDomains) { }
