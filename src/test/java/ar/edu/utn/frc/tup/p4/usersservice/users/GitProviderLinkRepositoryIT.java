package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.GitProviderLink;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.GitProviderLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Etapa 1 criteria 1–4: uniqueness over active rows on real MySQL. */
class GitProviderLinkRepositoryIT extends AbstractIntegrationTest {

    @Autowired GitProviderLinkRepository repo;

    @Test
    void migration_applies_and_a_link_can_be_persisted() {
        UUID userId = UUID.randomUUID();
        GitProviderLink saved = repo.saveAndFlush(
                GitProviderLink.create(userId, GitProvider.GITHUB, "ext-" + userId, "octocat"));
        assertThat(repo.findById(saved.getId())).isPresent();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void two_active_links_same_provider_same_user_violate_uniqueness() {
        UUID userId = UUID.randomUUID();
        repo.saveAndFlush(GitProviderLink.create(userId, GitProvider.GITHUB, "ext-a-" + userId, "a"));
        assertThatThrownBy(() -> repo.saveAndFlush(
                GitProviderLink.create(userId, GitProvider.GITHUB, "ext-b-" + userId, "b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void two_users_same_external_user_id_active_violate_uniqueness() {
        String externalId = "shared-" + UUID.randomUUID();
        repo.saveAndFlush(GitProviderLink.create(UUID.randomUUID(), GitProvider.GITHUB, externalId, "one"));
        assertThatThrownBy(() -> repo.saveAndFlush(
                GitProviderLink.create(UUID.randomUUID(), GitProvider.GITHUB, externalId, "two")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unlink_then_relink_same_or_different_account_does_not_violate_uniqueness() {
        UUID userId = UUID.randomUUID();
        String firstExt = "first-" + UUID.randomUUID();
        GitProviderLink first = repo.saveAndFlush(
                GitProviderLink.create(userId, GitProvider.GITHUB, firstExt, "first"));
        first.softDelete();
        repo.saveAndFlush(first);

        // Same external account again after soft-delete.
        repo.saveAndFlush(GitProviderLink.create(userId, GitProvider.GITHUB, firstExt, "first"));

        GitProviderLink secondActive = repo.findByUserIdAndProviderAndDeletedAtIsNull(userId, GitProvider.GITHUB)
                .orElseThrow();
        secondActive.softDelete();
        repo.saveAndFlush(secondActive);

        // Different account after another soft-delete.
        repo.saveAndFlush(GitProviderLink.create(
                userId, GitProvider.GITHUB, "other-" + UUID.randomUUID(), "other"));
        assertThat(repo.findByUserIdAndDeletedAtIsNull(userId)).hasSize(1);
    }
}
