package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.config.OpenApiConfig;
import ar.edu.utn.frc.tup.p4.usersservice.shared.gates.SkipAccountGate;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.auth.store.EphemeralTokenService;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.GitLinkCallbackRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.GitLinkResponse;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.GitLinkStartResponse;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.GitProviderLinkView;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderClientRegistry;
import ar.edu.utn.frc.tup.p4.usersservice.users.providers.GitProviderIdentity;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.GitProviderLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The two-step linking flow (SPEC-git-provider-linking §3).
 *
 * `start` hands the browser a consent URL plus a single-use {@code state};
 * `callback` redeems the provider grant and persists the link. DELETE/GET are
 * plain profile operations. Errors are never 401: a bad callback is not a dead
 * session (non-negotiable 3).
 */
@Tag(name = "Git provider links", description = "Links the account to a git hosting provider (OAuth).")
@SecurityRequirement(name = OpenApiConfig.COOKIE_SCHEME)
@RestController
@RequestMapping("${app.api.private-path}/me/git-links")
public class GitProviderLinkController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitProviderLinkService links;
    private final GitProviderClientRegistry registry;
    private final EphemeralTokenService ephemeral;
    private final Duration stateTtl;

    public GitProviderLinkController(GitProviderLinkService links,
                                     GitProviderClientRegistry registry,
                                     EphemeralTokenService ephemeral,
                                     @Value("${users.git-providers.github.state-ttl:PT5M}") Duration stateTtl) {
        this.links = links;
        this.registry = registry;
        this.ephemeral = ephemeral;
        this.stateTtl = stateTtl;
    }

    /**
     * Step 1. POST, not GET: it allocates single-use state, so a cross-site
     * {@code <img>} must not be able to trigger it. DEC-GL-18: with an active
     * link this answers 409 immediately, without sending anyone to the provider.
     */
    @Operation(summary = "Starts the provider linking flow",
            description = "Returns the provider consent URL. Exit of the onboarding gate, "
                    + "exempt from PASSWORD and ONBOARDING (both, non-negotiable 4).")
    @ApiResponse(responseCode = "200", description = "Consent URL issued.")
    @ApiResponse(responseCode = "409", description = "`type`: `provider-already-linked`.")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    @PostMapping("/{provider}/start")
    public GitLinkStartResponse start(@AuthenticationPrincipal GatewayPrincipal p,
                                      @PathVariable String provider) {
        UUID userId = requirePerson(p);
        GitProvider gp = parseProvider(provider);
        boolean alreadyLinked = links.listActive(userId).stream()
                .anyMatch(v -> v.provider() == gp);
        if (alreadyLinked) throw ApiException.providerAlreadyLinked();

        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String state = HexFormat.of().formatHex(raw);
        // DEC-GL-09: plain "userId:provider" — no JSON until a third field is needed.
        ephemeral.save("gitlink:" + state, userId + ":" + gp.name(), stateTtl);
        return new GitLinkStartResponse(registry.require(gp).authorizationUrl(state).toString());
    }

    /**
     * Step 2, in the non-negotiable order of §3.1. The state is consumed FIRST
     * (DEC-GL-22): any later failure still burns it, so a state is one attempt,
     * never an oracle to probe. The userId comes from the state; the X-*
     * headers only confirm it (DEC-GL-03). A mismatch is 409, never 401
     * (DEC-GL-16).
     */
    @Operation(summary = "Completes the provider linking flow",
            description = "Redeems the provider grant and persists the link. A successful link "
                    + "with the tour done clears first_login (DEC-GL-14). Same gate exemptions as start.")
    @ApiResponse(responseCode = "200", description = "Linked.")
    @SkipAccountGate({SkipAccountGate.Gate.PASSWORD, SkipAccountGate.Gate.ONBOARDING})
    @PostMapping("/{provider}/callback")
    public GitLinkResponse callback(@AuthenticationPrincipal GatewayPrincipal p,
                                    @PathVariable String provider,
                                    @Valid @RequestBody GitLinkCallbackRequest body) {
        UUID callerId = requirePerson(p);
        GitProvider gp = parseProvider(provider);
        registry.require(gp);

        String saved = ephemeral.consume("gitlink:" + body.state())
                .orElseThrow(ApiException::invalidLinkState);
        int sep = saved.lastIndexOf(':');
        if (sep < 0) throw ApiException.invalidLinkState();
        UUID stateUser;
        try {
            stateUser = UUID.fromString(saved.substring(0, sep));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidLinkState();
        }
        // A stored value that does not name a known provider is a corrupt
        // state, not an unsupported provider: 400 invalid-link-state (§3.5 #4).
        GitProvider stateProvider;
        try {
            stateProvider = GitProvider.valueOf(saved.substring(sep + 1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidLinkState();
        }
        if (stateProvider != gp) throw ApiException.invalidLinkState();
        if (!stateUser.equals(callerId)) throw ApiException.linkUserMismatch();

        GitProviderIdentity identity;
        try {
            identity = registry.require(gp).exchange(body.code());
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.providerUnavailable();
        }
        links.link(stateUser, gp, identity);
        return new GitLinkResponse(gp, identity.username());
    }

    /** Profile operation, not a gate exit: the full gates apply. */
    @Operation(summary = "Unlinks the provider account")
    @ApiResponse(responseCode = "204", description = "Unlinked.")
    @ApiResponse(responseCode = "404", description = "`type`: `provider-not-linked`.")
    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal GatewayPrincipal p,
                                       @PathVariable String provider) {
        links.unlink(requirePerson(p), parseProvider(provider));
        return ResponseEntity.noContent().build();
    }

    /** Profile read, not a gate exit: the full gates apply (DEC-GL-13). */
    @Operation(summary = "Lists the active provider links")
    @ApiResponse(responseCode = "200", description = "Active links (empty when none).")
    @GetMapping
    public List<GitProviderLinkView> list(@AuthenticationPrincipal GatewayPrincipal p) {
        return links.listActive(requirePerson(p));
    }

    /**
     * The {provider} path segment is parsed by NAME. Unknown values are 400
     * provider-not-supported — never a conversion 500 or the generic 404 (§3.3).
     */
    private GitProvider parseProvider(String raw) {
        try {
            return GitProvider.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.providerNotSupported();
        }
    }

    /**
     * 403, not 401: the identity is present but of the wrong kind (a service
     * token has no account to link). Same rule as AuthPrivateController.
     */
    private UUID requirePerson(GatewayPrincipal p) {
        if (p == null || !p.isPerson() || p.id() == null) throw ApiException.accessDenied();
        return p.id();
    }
}
