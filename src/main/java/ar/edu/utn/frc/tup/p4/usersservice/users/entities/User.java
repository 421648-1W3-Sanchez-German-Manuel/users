package ar.edu.utn.frc.tup.p4.usersservice.users.entities;

import ar.edu.utn.frc.tup.p4.usersservice.users.InvalidTransitionException;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

import static ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus.*;

@Entity
@Table(name = "users")
public class User {

    /** DEC-21 - DoD criterion #22. */
    private static final Map<AccountStatus, Set<AccountStatus>> VALID_TRANSITIONS = Map.of(
            PENDING_EMAIL, EnumSet.of(ACTIVE, PENDING_COURSE, DEACTIVATED),
            PENDING_COURSE, EnumSet.of(ACTIVE, DEACTIVATED),
            ACTIVE,          EnumSet.of(DEACTIVATED),
            DEACTIVATED,            EnumSet.noneOf(AccountStatus.class));

    @Id
    @Column(columnDefinition = "CHAR(36)")      // DEC-20 rule 1
    @JdbcTypeCode(SqlTypes.CHAR)                // Bind as CHAR(36) rather than bytes: the column is utf8mb4
    private UUID id;

    @Column(name = "first_names")
private String firstNames;
    @Column(name = "last_names")
private String lastNames;
    private String legajo;
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status")
    private AccountStatus accountStatus;

    @Column(name = "email_verified")      private boolean emailVerified;
    @Column(name = "must_change_password") private boolean mustChangePassword;
    @Column(name = "github_username")       private String  githubUsername;
    @Column(name = "avatar_ref")            private String  avatarRef;
    @Column(name = "first_login")          private boolean firstLogin;
    @Column(name = "guided_tour_completed") private boolean guidedTourCompleted;
    @Column(name = "terms_version_accepted")  private String  acceptedTermsVersion;
    @Column(name = "terms_accepted_at")       private Instant termsAcceptedAt;
    @Column(name = "created_at")            private Instant createdAt;
    @Column(name = "updated_at")            private Instant updatedAt;
    @Column(name = "deleted_at")            private Instant deletedAt;

    protected User() { }   // JPA

    public static User create(String firstNames, String lastNames, String email,
                             String passwordHash, Role role, String termsVersion) {
        User u = new User();
        u.id = UUID.randomUUID();
        u.firstNames = firstNames;
        u.lastNames = lastNames;
        u.email = email.toLowerCase(Locale.ROOT);   // DEC-20 rule 4
        u.passwordHash = passwordHash;
        u.role = role;
        u.accountStatus = PENDING_EMAIL;
        u.firstLogin = true;
        u.acceptedTermsVersion = termsVersion;
        u.termsAcceptedAt = Instant.now();
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        return u;
    }

    /** An ADMIN is created straight into ACTIVE: it skips e-mail activation. */
    public static User createAdmin(String firstNames, String lastNames, String email,
                                  String passwordHash, String termsVersion) {
        User u = create(firstNames, lastNames, email, passwordHash, Role.ADMIN, termsVersion);
        u.accountStatus = ACTIVE;
        u.emailVerified = true;
        u.mustChangePassword = true;    // RF-USR-01
        return u;
    }

    private void transitionTo(AccountStatus target) {
        if (!VALID_TRANSITIONS.get(this.accountStatus).contains(target)) {
            throw new InvalidTransitionException(this.accountStatus, target);
        }
        this.accountStatus = target;
        this.updatedAt = Instant.now();
    }

    /** PENDING_EMAIL -> ACTIVE (PROFESSOR/ADMIN) or -> PENDING_COURSE (STUDENT). */
    public void activate() {
        transitionTo(role == Role.STUDENT ? PENDING_COURSE : ACTIVE);
        this.emailVerified = true;
    }

    /** PENDING_COURSE -> ACTIVE. A no-op if it was already ACTIVE (idempotency, DEC-13). */
    public void activateAfterCourseValidation() {
        if (this.accountStatus == ACTIVE) return;
        transitionTo(ACTIVE);
    }

    public void deactivate() {
        transitionTo(DEACTIVATED);
        this.deletedAt = Instant.now();
    }

    /** DEC-30: avatarRef is OPTIONAL while object storage is out of this sprint. */
    public void completeOnboarding(String githubUsername, String avatarRef, boolean tourOk) {
        this.githubUsername = githubUsername;
        this.avatarRef = avatarRef;
        this.guidedTourCompleted = tourOk;
        this.firstLogin = false;
        this.updatedAt = Instant.now();
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
        this.updatedAt = Instant.now();
    }

    public void requirePasswordChange() { this.mustChangePassword = true; }
    public void changeRole(Role newRole) { this.role = newRole; this.updatedAt = Instant.now(); }

    /** Test-only: builds a User in an arbitrary state, bypassing the transition table. */
    public void forceStatusForTest(AccountStatus status) { this.accountStatus = status; }

    public UUID getId() { return id; }
    public String getFirstNames() { return firstNames; }
    public String getLastNames() { return lastNames; }
    public String getLegajo() { return legajo; }
    public void setLegajo(String legajo) { this.legajo = legajo; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public AccountStatus getAccountStatus() { return accountStatus; }
    public boolean isEmailVerified() { return emailVerified; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public String getGithubUsername() { return githubUsername; }
    public String getAvatarRef() { return avatarRef; }
    public boolean isFirstLogin() { return firstLogin; }
    public boolean isGuidedTourCompleted() { return guidedTourCompleted; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getTermsAcceptedAt() { return termsAcceptedAt; }
    public String getAcceptedTermsVersion() { return acceptedTermsVersion; }
}
