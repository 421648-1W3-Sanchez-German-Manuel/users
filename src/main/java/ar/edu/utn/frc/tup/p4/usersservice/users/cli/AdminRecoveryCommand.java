package ar.edu.utn.frc.tup.p4.usersservice.users.cli;

import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * RF-ROL-04 - the legitimate, audited back door. A CLI command, with NO
 * HTTP endpoint: as an endpoint it would be a privilege escalation to
 * within one request distance.
 *
 * Scenario: the platform was left without any active ADMIN (bug, backup
 * restore, human error). validateNotLastAdmin prevents reaching zero through
 * normal paths, but if it happens anyway there is no way in through the UI.
 */
@Component
public class AdminRecoveryCommand {

    private static final Logger log = LoggerFactory.getLogger(AdminRecoveryCommand.class);

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final String secretHash;
    private final String tycVigente;

    public AdminRecoveryCommand(UserRepository repo, PasswordEncoder encoder,
                                @Value("${users.breakglass.secret-hash:}") String secretHash,
                                @Value("${users.legal.terms-version}") String tycVigente) {
        this.repo = repo;
        this.encoder = encoder;
        this.secretHash = secretHash;
        this.tycVigente = tycVigente;
    }

    public UUID recuperar(String secreto, String firstNames, String lastNames,
                          String email, String password) {
        // Point 1 of the RF: installation secret, compared against a hash.
        if (secretHash.isBlank() || !encoder.matches(secreto, secretHash)) {
            throw new SecurityException("Invalid installation secret.");
        }
        PasswordPolicy.validate(password);

        User admin = User.createAdmin(firstNames, lastNames, email,
                encoder.encode(password), tycVigente);
        UUID id = repo.saveAndFlush(admin).getId();

        alert(id);
        return id;
    }

    /**
     * DEC-32 - log-only alert. The admin IS created regardless.
     */
    private void alert(UUID adminId) {
        log.error("BREAKGLASS_USED adminCreated={}", adminId);
    }
}
