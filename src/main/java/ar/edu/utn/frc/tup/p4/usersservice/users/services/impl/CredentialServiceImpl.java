package ar.edu.utn.frc.tup.p4.usersservice.users.services.impl;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.PasswordPolicy;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.CredentialService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CredentialServiceImpl implements CredentialService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;

    public CredentialServiceImpl(UserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Override
    @Transactional(readOnly = true)
    public VerifiedCredentials verifyCredentials(String email, String plainPassword) {
        return repo.findByEmailAndDeletedAtIsNull(email.toLowerCase(Locale.ROOT))
                .filter(u -> encoder.matches(plainPassword, u.getPasswordHash()))
                .map(u -> new VerifiedCredentials(
                        u.getId(), List.of(u.getRole()), u.getAccountStatus(),
                        u.mustChangePassword(), u.getEmail(), u.getFirstNames()))
                .orElse(null);   // does not tell "does not exist" from "wrong password"
    }

    @Override
    @Transactional
    public void updatePassword(UUID userId, String newPlainPassword) {
        PasswordPolicy.validate(newPlainPassword);
        User u = repo.findByIdAndDeletedAtIsNull(userId).orElseThrow(ApiException::invalidCredentials);
        u.changePassword(encoder.encode(newPlainPassword));
        repo.save(u);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyPasswordOf(UUID userId, String plainPassword) {
        return repo.findByIdAndDeletedAtIsNull(userId)
                .map(u -> encoder.matches(plainPassword, u.getPasswordHash()))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public TokenData tokenData(UUID userId) {
        User u = repo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::invalidCredentials);
        return new TokenData(List.of(u.getRole()), u.getAccountStatus(),
                u.mustChangePassword(), u.isFirstLogin());
    }

    @Override
    @Transactional(readOnly = true)
    public ResetData findForPasswordReset(String email) {
        return repo.findByEmailAndDeletedAtIsNull(email.toLowerCase(Locale.ROOT))
                .map(u -> new ResetData(u.getId(), u.getEmail(), u.getFirstNames()))
                .orElse(null);
    }
}