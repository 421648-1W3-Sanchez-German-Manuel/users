package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.config.OpenApiConfig;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Users",
     description = "Own account, profiles, and user administration.")
@SecurityRequirement(name = OpenApiConfig.COOKIE_SCHEME)
@RestController
@RequestMapping("${app.api.private-path}")
public class UserController {

    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    /**
     * Exempt from all three gates. This route tells the person WHY access is
     * blocked: if a gate intercepted it, the account would be a black box and
     * the frontend could not explain the block.
     */
    @Operation(summary = "The complete current account",
               description = """
                        Exempt from all THREE gates, and the only route exempt for this reason: it
                        tells the person WHY access is blocked. If a gate intercepted it, the
                        account would be a black box and the frontend could not explain the block.

                        Inspect `accountStatus`, `mustChangePassword`, and `firstLogin` to determine
                        which screen to show before allowing access to the rest of the application.""")
    @ApiResponse(responseCode = "200", description = "The current account, including email and legajo.")
    @SkipAccountGate({SkipAccountGate.Gate.ACCOUNT_STATUS, SkipAccountGate.Gate.PASSWORD,
                      SkipAccountGate.Gate.ONBOARDING})
    @GetMapping("/me")
    public UserMeResponse me(@AuthenticationPrincipal GatewayPrincipal p) {
        return users.me(p.id());
    }

    /**
     * Exit of the onboarding tour step. Exempt from both fine-grained gates
     * (non-negotiable 4). With GitHub enabled this alone does NOT clear
     * first_login — that needs a successful link (DEC-GL-14).
     */
    @Operation(summary = "Confirms the guided tour",
               description = """
                        Marks the guided tour as completed. Exempt from both fine-grained gates
                        (PASSWORD and ONBOARDING). With GitHub linking enabled this does NOT clear
                        `firstLogin` by itself — a successful provider link is also required
                        (DEC-GL-14). When GitHub is disabled, the tour alone is enough (DEC-GL-05).

                        Body: `{ "tourOk": true }` only. Username and avatar are no longer accepted
                        (DEC-GL-11, DEC-GL-21).""")
    @ApiResponse(responseCode = "200", description = "Tour recorded. The onboarding gate may still block until GitHub is linked.")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    @PatchMapping("/me/onboarding")
    public void onboarding(@AuthenticationPrincipal GatewayPrincipal p,
                           @Valid @RequestBody OnboardingRequest r) {
        users.completeOnboarding(p.id(), r.tourOk());
    }

    @Operation(summary = "Another person's public profile",
               description = """
                        What any classmate can see: name, GitHub username, and avatar.
                        **Does NOT include email, legajo, or account status**; use `/me` for that,
                        which returns the current user's OWN account.""")
    @ApiResponse(responseCode = "200", description = "Public profile.")
    @ApiResponse(responseCode = "403", description = """
            `type`: `access-denied`. A service token without the `users.profile.read` scope.""")
    @ApiResponse(responseCode = "404", description = "`type`: `route-not-found`.")
    @GetMapping("/profile/{id}")
    // A person: any classmate, which is what this endpoint is for. A SERVICE:
    // only with the scope the token was issued for.
    //
    // Until now nothing checked the scope anywhere in this service — it was
    // validated on issue, signed, propagated as X-Service-Scopes and turned
    // into a GrantedAuthority by GatewayIdentityFilter, and then never read.
    // It happened to be harmless only because ScopeCatalog has a single
    // issuable scope, so every service token carried exactly this one. The day
    // a second one exists, a token issued for `cursos.*` would walk into this
    // endpoint unless somebody had added this line first.
    @PreAuthorize("principal.isPerson() or hasAuthority('users.profile.read')")
    public ProfileResponse profile(@PathVariable UUID id) {
        return users.profile(id);
    }

    /** ADMIN/GESTOR directory. Only active accounts, newest first. */
    @Operation(summary = "User directory (ADMIN/GESTOR)",
               description = """
                        ACTIVE accounts only, newest first.

                        ADMIN sees the complete directory. GESTOR sees only PROFESSOR/GESTOR
                        accounts, never ADMIN or STUDENT.""")
    @ApiResponse(responseCode = "200", description = "List of active accounts.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public List<UserListItemResponse> list(@AuthenticationPrincipal GatewayPrincipal p) {
        return users.list(p.id());
    }

    /**
     * Layer 1 in the annotation (does it have the role?). Layer 2 in the service
     * (does it leave the platform without an ADMIN?). ADMIN-only: it creates
     * only ADMIN accounts (see CreateUserRequest) — GESTOR accounts come in
     * through the whitelist, like PROFESSOR.
     */
    @Operation(summary = "Creates an ADMIN (ADMIN)",
               description = """
                        **Creates ADMIN only.** PROFESSOR and STUDENT enter only through the
                        whitelist plus self-registration: an ADMIN creating them directly with a
                        password would leave them in `PENDING_EMAIL` without an activation link,
                        making the account impossible to activate.

                        The account starts with a forced password change and pending onboarding.""")
    @ApiResponse(responseCode = "200", description = "Created. Returns the `id`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`.")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> create(@Valid @RequestBody CreateUserRequest r) {
        return Map.of("id", users.create(r.firstNames(), r.lastNames(), r.email(),
                r.password()).toString());
    }

    /** Layer 1 here, layer 2 (a GESTOR may only touch PROFESSOR/GESTOR) in the service. */
    @Operation(summary = "Deactivates an account (ADMIN/GESTOR)",
               description = """
                        LOGICAL deactivation: nothing is physically deleted (non-negotiable 6).

                        Requires complete reauthentication in the body, **whatever the target's
                        role** (`RF-ROL-06`, SPEC §16.3): the current password, a FRESH 2FA code,
                        and the target's username typed by hand. The code is the one the screen
                        requests immediately before, and it is single-use — one challenge buys
                        exactly one deactivation.

                        The reinforcement is about who is ASKING, so it does not depend on the
                        target: a stolen session deactivating fifty STUDENT accounts is not a
                        smaller incident than one deactivating a single ADMIN.

                        Two distinct defensive layers: the annotation checks the role, and the
                        service checks whether the operation would leave the platform without an
                        active ADMIN or whether a GESTOR is trying to deactivate someone outside
                        the PROFESSOR/GESTOR scope (neither ADMIN nor STUDENT). An ADMIN also cannot
                        deactivate their own account.

                        On success the target's session is closed (`session:{userId}` is deleted,
                        DEC-22) and an `ACCOUNT-DEACTIVATED` event goes out on `user-events`.""")
    @ApiResponse(responseCode = "200", description = "Account deactivated.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `invalid-code`. The 2FA code is wrong or expired. Ask for a new one; five
            failures within an hour discard the challenge.""")
    @ApiResponse(responseCode = "401", description = "`type`: `invalid-credentials`. Reauthentication failed.")
    @ApiResponse(responseCode = "403", description = "`type`: `access-denied`. A GESTOR attempted to exceed the PROFESSOR/GESTOR scope.")
    @ApiResponse(responseCode = "409", description = """
            `type`: `last-admin`. The platform cannot be left without an active ADMIN.""")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void deactivate(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                           @Valid @RequestBody AdminDeactivationRequest req) {
        users.deactivate(p.id(), id, req);
    }

    @Operation(summary = "Changes an account role (ADMIN/GESTOR)",
               description = """
                        The same two defensive layers as deactivation: demoting the last active
                        ADMIN would leave the platform without an administrator, so the service
                        blocks it even when an ADMIN requests it.

                        A GESTOR can move PROFESSOR/GESTOR accounts only between those two roles:
                        it cannot modify an ADMIN account or grant ADMIN, and it cannot modify or
                        create STUDENT accounts this way.""")
    @ApiResponse(responseCode = "200", description = "Role changed.")
    @ApiResponse(responseCode = "403", description = "`type`: `access-denied`. A GESTOR attempted to exceed the PROFESSOR/GESTOR scope.")
    @ApiResponse(responseCode = "409", description = "`type`: `last-admin`.")
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void changeRole(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                           @Valid @RequestBody RoleChangeRequest req) {
        users.changeRole(p.id(), id, req.role());
    }
}
