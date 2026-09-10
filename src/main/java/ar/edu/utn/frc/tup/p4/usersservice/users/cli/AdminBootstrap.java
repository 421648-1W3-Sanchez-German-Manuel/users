package ar.edu.utn.frc.tup.p4.usersservice.users.cli;

import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * RF-USR-01 - the initial ADMIN of a clean installation. Idempotent: only
 * created when no active ADMIN exists.
 *
 * Having no fixed default password is deliberate: a default password in the
 * repository is the same one in every installation.
 */
@Component
@ConditionalOnProperty(name = "users.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final String email;
    private final String password;
    private final String firstNames;
    private final String lastNames;
    private final String tycVigente;

    public AdminBootstrap(UserRepository repo, PasswordEncoder encoder,
                          TransactionTemplate tx,
                          @Value("${users.bootstrap.email:admin@frc.utn.edu.ar}") String email,
                          @Value("${users.bootstrap.password:}") String password,
                          @Value("${users.bootstrap.first-names:Admin}") String firstNames,
                          @Value("${users.bootstrap.last-names:Inicial}") String lastNames,
                          @Value("${users.legal.terms-version}") String tycVigente) {
        this.repo = repo;
        this.encoder = encoder;
        this.tx = tx;
        this.email = email;
        this.password = password;
        this.firstNames = firstNames;
        this.lastNames = lastNames;
        this.tycVigente = tycVigente;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repo.countByRoleAndDeletedAtIsNull(Role.ADMIN) > 0) return;   // idempotent
        if (repo.findByEmailAndDeletedAtIsNull(email.toLowerCase()).isPresent()) {
            log.warn("ADMIN_BOOTSTRAP omitted: {} already exists with another role.", email);
            return;
        }

        boolean generated = password.isBlank();
        String clear = generated ? generate() : password;
        PasswordPolicy.validate(clear);

        UUID id = tx.execute(s -> {
            User admin = User.createAdmin(firstNames, lastNames, email.toLowerCase(),
                    encoder.encode(clear), tycVigente);
            return repo.saveAndFlush(admin).getId();
        });

        if (generated) log.warn("""

                ====================================================================
                 INITIAL ADMIN CREATED (RF-USR-01)
                   email:    {}
                   password: {}
                 Shown only once. Must be changed on first login.
                 To set it yourself: ADMIN_BOOTSTRAP_PASSWORD env var.
                ====================================================================
                """, email, clear);
    }

    private String generate() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return "Aa1" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
