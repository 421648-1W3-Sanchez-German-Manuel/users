package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ErrorTypes;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.GitProviderLink;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClient;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClientRegistry;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.GitProviderLinkRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitProviderLinkServiceTest {

    @Mock GitProviderLinkRepository links;
    @Mock UserRepository users;

    User user;

    @BeforeEach
    void setUp() {
        user = User.create("Ana", "P", "ana-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        user.forceStatusForTest(AccountStatus.ACTIVE);
    }

    private UUID id() { return user.getId(); }

    /** Stands in for a registered adapter so the flag alone drives these cases. */
    private static final GitProviderClient GITHUB_ADAPTER = new GitProviderClient() {
        @Override public GitProvider provider() { return GitProvider.GITHUB; }
        @Override public URI authorizationUrl(String state) { return URI.create("https://github.test/" + state); }
        @Override public GitProviderIdentity exchange(String code) { return new GitProviderIdentity("1", "octocat"); }
    };

    private GitProviderLinkService service(boolean githubEnabled) {
        return new GitProviderLinkService(links, users,
                new GitProviderClientRegistry(List.of(GITHUB_ADAPTER)), githubEnabled);
    }

    /** Enabled but with nothing registered: the requirement cannot be met, so it does not apply. */
    private GitProviderLinkService serviceWithoutAdapter() {
        return new GitProviderLinkService(links, users,
                new GitProviderClientRegistry(List.of()), true);
    }

    private void stubActiveUser() {
        when(users.findByIdAndDeletedAtIsNull(id())).thenReturn(Optional.of(user));
    }

    @Test
    void link_inserts_mirrors_username_and_closes_onboarding_when_ready() {
        stubActiveUser();
        user.completeOnboarding(true);
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.empty());
        when(links.findByProviderAndExternalUserIdAndDeletedAtIsNull(GitProvider.GITHUB, "42"))
                .thenReturn(Optional.empty());
        when(links.existsByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(true);

        service(true).link(id(), GitProvider.GITHUB, new GitProviderIdentity("42", "octocat"));

        ArgumentCaptor<GitProviderLink> captor = ArgumentCaptor.forClass(GitProviderLink.class);
        verify(links).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getExternalUserId()).isEqualTo("42");
        assertThat(captor.getValue().getUsername()).isEqualTo("octocat");
        assertThat(user.getGithubUsername()).isEqualTo("octocat");
        assertThat(user.isFirstLogin()).isFalse();
        verify(users).save(user);
    }

    @Test
    void link_fails_when_already_linked() {
        stubActiveUser();
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.of(GitProviderLink.create(id(), GitProvider.GITHUB, "1", "x")));

        assertThatThrownBy(() -> service(false).link(
                id(), GitProvider.GITHUB, new GitProviderIdentity("2", "y")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getType())
                        .isEqualTo(ErrorTypes.PROVIDER_ALREADY_LINKED));
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void link_fails_when_external_account_is_taken() {
        stubActiveUser();
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.empty());
        when(links.findByProviderAndExternalUserIdAndDeletedAtIsNull(GitProvider.GITHUB, "42"))
                .thenReturn(Optional.of(GitProviderLink.create(UUID.randomUUID(), GitProvider.GITHUB, "42", "x")));

        assertThatThrownBy(() -> service(false).link(
                id(), GitProvider.GITHUB, new GitProviderIdentity("42", "octocat")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getType())
                        .isEqualTo(ErrorTypes.PROVIDER_ACCOUNT_TAKEN));
    }

    @Test
    void unlink_marks_deleted_clears_mirror_and_does_not_touch_first_login() {
        stubActiveUser();
        user.completeOnboarding(true);
        user.clearFirstLogin();
        user.mirrorGithubUsername("octocat");
        GitProviderLink link = GitProviderLink.create(id(), GitProvider.GITHUB, "42", "octocat");
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.of(link));

        service(true).unlink(id(), GitProvider.GITHUB);

        assertThat(link.getDeletedAt()).isNotNull();
        assertThat(user.getGithubUsername()).isNull();
        assertThat(user.isFirstLogin()).isFalse();
        verify(links).save(link);
        verify(users).save(user);
    }

    @Test
    void unlink_without_active_link_is_provider_not_linked() {
        stubActiveUser();
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(false).unlink(id(), GitProvider.GITHUB))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getType())
                        .isEqualTo(ErrorTypes.PROVIDER_NOT_LINKED));
    }

    @Test
    void closeOnboardingIfReady_covers_DEC_GL_14_table() {
        // PATCH tour, GitHub enabled, no link → first_login stays true
        user.completeOnboarding(true);
        when(links.existsByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(false);
        service(true).closeOnboardingIfReady(user);
        assertThat(user.isFirstLogin()).isTrue();

        // callback OK, tour already done → first_login false
        when(links.existsByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(true);
        service(true).closeOnboardingIfReady(user);
        assertThat(user.isFirstLogin()).isFalse();

        // callback OK, tour not done → first_login stays true
        User noTour = User.create("B", "Q", "b-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        noTour.forceStatusForTest(AccountStatus.ACTIVE);
        service(true).closeOnboardingIfReady(noTour);
        assertThat(noTour.isFirstLogin()).isTrue();

        // PATCH tour, GitHub disabled → first_login false (escape)
        User escape = User.create("C", "R", "c-" + UUID.randomUUID() + "@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        escape.forceStatusForTest(AccountStatus.ACTIVE);
        escape.completeOnboarding(true);
        service(false).closeOnboardingIfReady(escape);
        assertThat(escape.isFirstLogin()).isFalse();
    }

    @Test
    void unlinkAllActive_releases_links_even_when_account_is_not_ACTIVE() {
        when(users.findByIdAndDeletedAtIsNull(id())).thenReturn(Optional.of(user));
        user.forceStatusForTest(AccountStatus.PENDING_EMAIL);
        user.mirrorGithubUsername("octocat");
        GitProviderLink link = GitProviderLink.create(id(), GitProvider.GITHUB, "42", "octocat");
        when(links.findByUserIdAndDeletedAtIsNull(id())).thenReturn(List.of(link));

        service(false).unlinkAllActive(id());

        assertThat(link.getDeletedAt()).isNotNull();
        assertThat(user.getGithubUsername()).isNull();
        verify(links).saveAll(List.of(link));
        verify(users).save(user);
    }

    @Test
    void listActive_maps_views() {
        stubActiveUser();
        GitProviderLink link = GitProviderLink.create(id(), GitProvider.GITHUB, "42", "octocat");
        when(links.findByUserIdAndDeletedAtIsNull(id())).thenReturn(List.of(link));

        assertThat(service(false).listActive(id()))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.provider()).isEqualTo(GitProvider.GITHUB);
                    assertThat(v.username()).isEqualTo("octocat");
                    assertThat(v.linkedAt()).isEqualTo(link.getCreatedAt());
                });
    }

    @Test
    void enabled_without_a_registered_adapter_still_closes_onboarding() {
        User ready = User.create("Ada", "L", "ready@utn.edu.ar", "$2a$12$h", Role.STUDENT, "v1");
        ready.forceStatusForTest(AccountStatus.ACTIVE);
        ready.completeOnboarding(true);

        serviceWithoutAdapter().closeOnboardingIfReady(ready);

        assertThat(ready.isFirstLogin()).isFalse();
    }

    @Test
    void a_lost_insert_race_reports_the_link_conflict_not_duplicate_email() {
        stubActiveUser();
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.empty());
        when(links.findByProviderAndExternalUserIdAndDeletedAtIsNull(GitProvider.GITHUB, "42"))
                .thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry for key 'uq_git_link_active_provider_account'"));

        assertThatThrownBy(() -> service(true).link(
                id(), GitProvider.GITHUB, new GitProviderIdentity("42", "octocat")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getType())
                        .isEqualTo(ErrorTypes.PROVIDER_ACCOUNT_TAKEN));
    }

    @Test
    void a_lost_race_on_the_user_index_reports_provider_already_linked() {
        stubActiveUser();
        when(links.findByUserIdAndProviderAndDeletedAtIsNull(id(), GitProvider.GITHUB))
                .thenReturn(Optional.empty());
        when(links.findByProviderAndExternalUserIdAndDeletedAtIsNull(GitProvider.GITHUB, "42"))
                .thenReturn(Optional.empty());
        when(links.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry for key 'uq_git_link_active_user_provider'"));

        assertThatThrownBy(() -> service(true).link(
                id(), GitProvider.GITHUB, new GitProviderIdentity("42", "octocat")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getType())
                        .isEqualTo(ErrorTypes.PROVIDER_ALREADY_LINKED));
    }
}
