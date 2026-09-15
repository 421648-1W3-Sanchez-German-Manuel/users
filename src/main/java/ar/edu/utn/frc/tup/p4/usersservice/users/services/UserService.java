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

import java.util.List;
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
                u.getLegajo(), u.getEmail(), u.getRole(), u.getAccountStatus(),
                u.mustChangePassword(), u.isFirstLogin(), u.isGuidedTourCompleted(),
                u.getGithubUsername(), u.getAvatarRef(), u.isEmailVerified(), u.getCreatedAt(),
                u.getTermsAcceptedAt(), u.getAcceptedTermsVersion());
    }

    @Transactional(readOnly = true)
    public ProfileResponse perfil(UUID id) {
        User u = buscar(id);
        return new ProfileResponse(u.getId().toString(), u.getFirstNames(), u.getLastNames(),
                u.getGithubUsername(), u.getAvatarRef());
    }

    /**
     * ADMIN directory: every active account, newest first. A GESTOR gets the
     * same shape but scoped to PROFESSOR/GESTOR accounts — it must never see
     * ADMIN or STUDENT records here.
     */
    @Transactional(readOnly = true)
    public List<UserListItemResponse> listar(UUID actorId) {
        boolean esGestor = buscar(actorId).getRole() == Role.GESTOR;
        List<User> usuarios = esGestor
                ? repo.findByRoleInAndDeletedAtIsNullOrderByCreatedAtDesc(List.of(Role.PROFESSOR, Role.GESTOR))
                : repo.findByDeletedAtIsNullOrderByCreatedAtDesc();
        return usuarios.stream()
                .map(u -> new UserListItemResponse(u.getId().toString(), u.getFirstNames(),
                        u.getLastNames(), u.getLegajo(), u.getEmail(), u.getRole(),
                        u.getAccountStatus(), u.getCreatedAt()))
                .toList();
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

        // A GESTOR manages PROFESSOR and GESTOR accounts only — never ADMIN or STUDENT.
        if (buscar(actorId).getRole() == Role.GESTOR
                && objetivo.getRole() != Role.PROFESSOR && objetivo.getRole() != Role.GESTOR) {
            throw ApiException.accessDenied();
        }

        if (buscar(actorId).getRole() == Role.GESTOR && actorId.equals(objetivoId)) {
            throw ApiException.validation("Un GESTOR no puede darse de baja a si mismo.");
        }

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

        // A GESTOR can only move PROFESSOR/GESTOR accounts between those two
        // roles: it can neither touch an existing ADMIN or STUDENT, nor grant
        // ADMIN or STUDENT.
        boolean fueraDeAlcance = (objetivo.getRole() != Role.PROFESSOR && objetivo.getRole() != Role.GESTOR)
                || (nuevo != Role.PROFESSOR && nuevo != Role.GESTOR);
        if (buscar(actorId).getRole() == Role.GESTOR && fueraDeAlcance) {
            throw ApiException.accessDenied();
        }

        if (buscar(actorId).getRole() == Role.GESTOR && actorId.equals(objetivoId)) {
            throw ApiException.validation("Un GESTOR no puede cambiarse el rol a si mismo.");
        }

        if (objetivo.getRole() == Role.ADMIN && nuevo != Role.ADMIN
                && repo.countActiveWithLock(Role.ADMIN) <= 1) {
            throw ApiException.lastAdmin();
        }
        objetivo.changeRole(nuevo);
        repo.save(objetivo);
    }

    /** RF-ROL-03 - este alta manual es solo para ADMIN; ver DTO. */
    @Transactional
    public UUID crear(String firstNames, String lastNames, String email, String password) {
        PasswordPolicy.validate(password);
        String normalizado = email.toLowerCase(Locale.ROOT);
        if (repo.findByEmailAndDeletedAtIsNull(normalizado).isPresent()) throw ApiException.duplicateEmail();

        User u = User.createAdmin(firstNames, lastNames, normalizado, encoder.encode(password), tycVigente);
        repo.saveAndFlush(u);
        return u.getId();
    }

    private User buscar(UUID id) {
        return repo.findByIdAndDeletedAtIsNull(id).orElseThrow(ApiException::accessDenied);
    }
}
