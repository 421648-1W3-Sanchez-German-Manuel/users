package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.config.OpenApiConfig;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.WhitelistRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.WhitelistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "Whitelist",
     description = """
             Emails authorized for professor registration, and the request queue reviewed
             by an ADMIN.""")
@SecurityRequirement(name = OpenApiConfig.COOKIE_SCHEME)
@RestController
@RequestMapping("${app.api.private-path}/whitelist")
public class WhitelistController {

    private final WhitelistService whitelist;

    public WhitelistController(WhitelistService whitelist) { this.whitelist = whitelist; }

    @Operation(summary = "Authorizes an email (ADMIN/GESTOR)",
               description = """
                        Direct addition without passing through the request queue. The email can
                        then self-register as PROFESSOR or GESTOR, according to `role`.

                        `role` is OPTIONAL and defaults to `PROFESSOR`; only `PROFESSOR` and
                        `GESTOR` are accepted (the whitelist never grants `ADMIN`).""")
    @ApiResponse(responseCode = "200", description = "Authorized. Returns the `id`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`. Already authorized.")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public Map<String, String> add(@AuthenticationPrincipal GatewayPrincipal p,
                                   @Valid @RequestBody AddEmailRequest r) {
        Role role = null;
        if (r.role() != null && !r.role().isBlank()) {
            try {
                role = Role.valueOf(r.role());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Invalid role: '" + r.role() + "'. Must be PROFESSOR or GESTOR.");
            }
        }
        return Map.of("id", whitelist.add(p.id(), r.email(), role).toString());
    }

    @Operation(summary = "Lists authorized emails (ADMIN/GESTOR)")
    @ApiResponse(responseCode = "200", description = "Each row contains `id`, `email`, `role`, and `createdAt`.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public List<Map<String, String>> list() {
        return whitelist.list().stream()
                .map(e -> {
                    Map<String, String> dto = new HashMap<>();
                    dto.put("id", e.getId().toString());
                    dto.put("email", e.getEmail());
                    dto.put("role", e.getRole().name());
                    if (e.getCreatedAt() != null) dto.put("createdAt", e.getCreatedAt().toString());
                    return dto;
                }).toList();
    }

    @Operation(summary = "Removes an email from the whitelist (ADMIN/GESTOR)",
               description = """
                        Does not affect accounts ALREADY created with that email: it removes
                        authorization to register and does not deactivate anyone.""")
    @ApiResponse(responseCode = "200", description = "Removed.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void remove(@PathVariable UUID id) { whitelist.remove(id); }

    @Operation(summary = "Requests email authorization (PROFESSOR)",
               description = """
                        The path for a professor who wants to add someone but cannot authorize the
                        email directly: the professor submits a request for an ADMIN to resolve.

                        There can be only ONE pending request per email.""")
    @ApiResponse(responseCode = "200", description = "Request created. Returns the `id`.")
    @ApiResponse(responseCode = "409", description = """
            `type`: `duplicate-email`. A pending request already exists for that email.""")
    @PostMapping("/requests")
    @PreAuthorize("hasRole('PROFESSOR')")
    public Map<String, String> request(@AuthenticationPrincipal GatewayPrincipal p,
                                       @Valid @RequestBody CreateWhitelistRequest r) {
        return Map.of("id", whitelist.request(p.id(), r.email(), r.reason()).toString());
    }

    /** DEC-29 · the ADMIN/GESTOR review queue: pending + resolved, newest first. */
    @Operation(summary = "Request review queue (ADMIN/GESTOR)",
               description = """
                        Pending AND resolved requests, newest first. Resolved requests deliberately
                        remain visible: without them there is no way to see what was rejected or why.""")
    @ApiResponse(responseCode = "200", description = """
            Each row contains `id`, `email`, `requestedBy`, `status`, `reason`, `rejectionReason`,
            and `createdAt`.""")
    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public List<Map<String, String>> listRequests() {
        return whitelist.listRequests().stream().map(this::toDto).toList();
    }

    private Map<String, String> toDto(WhitelistRequest r) {
        Map<String, String> dto = new HashMap<>();
        dto.put("id", r.getId().toString());
        dto.put("email", r.getRequestedEmail());
        dto.put("requestedBy", r.getRequestedBy().toString());
        dto.put("status", r.getStatus().name());
        dto.put("reason", r.getReason() == null ? "" : r.getReason());
        dto.put("rejectionReason", r.getRejectionReason() == null ? "" : r.getRejectionReason());
        if (r.getCreatedAt() != null) dto.put("createdAt", r.getCreatedAt().toString());
        return dto;
    }

    @Operation(summary = "Approves or rejects a request (ADMIN/GESTOR)",
               description = """
                        Approval authorizes the email as PROFESSOR. `rejectionReason` is required
                        when `approve` is `false`: a rejection without a reason is not useful.""")
    @ApiResponse(responseCode = "200", description = "Request resolved.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `validation`. Missing `rejectionReason` for a rejection, an already resolved
            request, or an email already authorized in the whitelist under another role.""")
    @PatchMapping("/requests/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void resolve(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                        @Valid @RequestBody ResolveWhitelistRequest r) {
        whitelist.resolve(p.id(), id, r.approve(), r.rejectionReason());
    }
}
