package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.GitProviderLinkView;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.GitProviderLink;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.GitProviderLinkRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class GitProviderLinkService {

    private final GitProviderLinkRepository links;
    private final UserRepository users;
    private final boolean githubEnabled;

    public GitProviderLinkService(GitProviderLinkRepository links, UserRepository users,
                                  @Value("${users.git-providers.github.enabled:false}") boolean githubEnabled) {
        this.links = links;
        this.users = users;
        this.githubEnabled = githubEnabled;
    }

    /** Idempotency NO: if there is already an active link for that provider, fail (DEC-GL-06). */
    @Transactional
    public void link(UUID userId, GitProvider provider, GitProviderIdentity identity) {
        User user = requireActiveUser(userId);

        // DEC-20 rule 5: check-then-act under FOR UPDATE; UNIQUE keys are the second line.
        if (links.findByUserIdAndProviderAndDeletedAtIsNull(userId, provider).isPresent()) {
            throw ApiException.providerAlreadyLinked();
        }
        if (links.findByProviderAndExternalUserIdAndDeletedAtIsNull(provider, identity.externalUserId())
                .isPresent()) {
            throw ApiException.providerAccountTaken();
        }

        links.saveAndFlush(GitProviderLink.create(
                userId, provider, identity.externalUserId(), identity.username()));

        // DEC-GL-11: mirror the provider login onto users.github_username.
        user.mirrorGithubUsername(identity.username());
        closeOnboardingIfReady(user); // DEC-GL-14
        users.save(user);
    }

    /** Soft-delete. After this, linking again is allowed (DEC-GL-15). */
    @Transactional
    public void unlink(UUID userId, GitProvider provider) {
        User user = requireActiveUser(userId);
        GitProviderLink link = links.findByUserIdAndProviderAndDeletedAtIsNull(userId, provider)
                .orElseThrow(ApiException::providerNotLinked);
        link.softDelete();
        links.save(link);
        // DEC-GL-13 / DEC-GL-15: clear the mirror so GET /me does not lie.
        user.clearGithubUsername();
        // Do NOT touch first_login or guided_tour_completed.
        users.save(user);
    }

    /**
     * DEC-GL-17: release every active link in the same transaction as account deactivation.
     * Must not require ACTIVE: deactivation itself is allowed from PENDING_EMAIL and
     * other live states. Does not reopen onboarding.
     */
    @Transactional
    public void unlinkAllActive(UUID userId) {
        User user = users.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::accessDenied);
        List<GitProviderLink> active = links.findByUserIdAndDeletedAtIsNull(userId);
        for (GitProviderLink link : active) {
            link.softDelete();
        }
        if (!active.isEmpty()) {
            links.saveAll(active);
            user.clearGithubUsername();
            users.save(user);
        }
    }

    @Transactional(readOnly = true)
    public List<GitProviderLinkView> listActive(UUID userId) {
        requireActiveUser(userId);
        return links.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(l -> new GitProviderLinkView(l.getProvider(), l.getUsername(), l.getLinkedAt()))
                .toList();
    }

    /**
     * DEC-GL-14. Called from link() and from completeOnboarding().
     * With GitHub enabled, first_login clears only when the tour is done AND there is an active link
     * (or GitHub is disabled — DEC-GL-05 escape).
     */
    void closeOnboardingIfReady(User user) {
        if (!user.isGuidedTourCompleted()) return;
        if (githubEnabled && !hasActiveGitHubLink(user.getId())) return;
        user.clearFirstLogin();
    }

    private boolean hasActiveGitHubLink(UUID userId) {
        return links.existsByUserIdAndProviderAndDeletedAtIsNull(userId, GitProvider.GITHUB);
    }

    private User requireActiveUser(UUID userId) {
        User user = users.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(ApiException::accessDenied);
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw ApiException.accessDenied();
        }
        return user;
    }
}
