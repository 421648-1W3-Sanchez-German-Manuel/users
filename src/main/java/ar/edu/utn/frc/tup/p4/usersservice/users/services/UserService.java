package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository repo;
    private final CredentialService credenciales;
    private final PasswordEncoder encoder;
    private final String tycVigente;

    public UserService(UserRepository repo, CredentialService credenciales,
                       PasswordEncoder encoder,
                       @Value("${users.legal.terms-version}") String tycVigente) {
        this.repo = repo;
        this.credenciales = credenciales;
        this.encoder = encoder;
        this.tycVigente = tycVigente;
    }

    @Transactional(readOnly = true)
    public UserMeResponse me(UUID id) {
        User u = buscar(id);
        return new UserMeResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getEmail(), u.getRole(), u.getAccountStatus(), u.mustChangePassword(),
                u.isFirstLogin(), u.isGuidedTourCompleted(), u.getGithubUsername(), u.getAvatarRef());
    }

    @Transactional(readOnly = true)
    public ProfileResponse perfil(UUID id) {
        User u = buscar(id);
        return new ProfileResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getGithubUsername(), u.getAvatarRef());
    }

    /** DEC-30 - avatarRef may be null while object storage is out of this sprint. */
    @Transactional
    public void completeOnboarding(UUID id, String githubUsername, String avatarRef, boolean tourOk) {
        User u = buscar(id);
        u.completeOnboarding(githubUsername, avatarRef, tourOk);
        repo.save(u);
    }

    /**
     * It crosses both modules with NO network in between: the reinforced confirmation
     * (password again + 2FA) belongs to auth/, the business rules to users/.
     */
    @Transactional
    public void deactivate(UUID actorId, UUID objetivoId, AdminDeactivationRequest req) {
        User objetivo = buscar(objetivoId);

        if (objetivo.getRole() == Role.ADMIN) {
            if (actorId.equals(objetivoId)) {
                throw ApiException.validation("Un ADMIN no puede darse de baja a si mismo.");
            }
            // RF-ROL-06 / DEC-11: written confirmation, not just a button.
            if (!objetivo.getEmail().equalsIgnoreCase(req.usernameConfirmation())) {
                throw ApiException.validation(
                        "Escribi el username exacto del ADMIN que vas a dar de baja para confirmar.");
            }
            if (!credenciales.verifyPasswordOf(actorId, req.password())) {
                throw ApiException.invalidCredentials();
            }

            // DEC-20 rule 5: the count goes WITH A LOCK, in this same transaction.
            if (repo.countActiveWithLock(Role.ADMIN) <= 1) throw ApiException.lastAdmin();
        }

        objetivo.deactivate();
        repo.save(objetivo);
    }

    @Transactional
    public void changeRole(UUID actorId, UUID objetivoId, Role nuevo) {
        User objetivo = buscar(objetivoId);
        if (objetivo.getRole() == Role.ADMIN && nuevo != Role.ADMIN
                && repo.countActiveWithLock(Role.ADMIN) <= 1) {
            throw ApiException.lastAdmin();
        }
        objetivo.changeRole(nuevo);
        repo.save(objetivo);
    }

    @Transactional
    public UUID crear(String firstNames, String lastNames, String email, String password, Role role) {
        PasswordPolicy.validate(password);
        String normalizado = email.toLowerCase(Locale.ROOT);
        if (repo.findByEmailAndDeletedAtIsNull(normalizado).isPresent()) throw ApiException.duplicateEmail();

        User u = role == Role.ADMIN
                ? User.createAdmin(firstNames, lastNames, normalizado, encoder.encode(password), tycVigente)
                : User.create(firstNames, lastNames, normalizado, encoder.encode(password), role, tycVigente);
        repo.saveAndFlush(u);
        return u.getId();
    }

    private User buscar(UUID id) {
        return repo.findByIdAndDeletedAtIsNull(id).orElseThrow(ApiException::accessDenied);
    }
}
