package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.WhitelistRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.WhitelistService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("${app.api.private-path}/whitelist")
public class WhitelistController {

    private final WhitelistService whitelist;

    public WhitelistController(WhitelistService whitelist) { this.whitelist = whitelist; }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public Map<String, String> agregar(@AuthenticationPrincipal GatewayPrincipal p,
                                       @Valid @RequestBody AddEmailRequest r) {
        return Map.of("id", whitelist.agregar(p.id(), r.email(), r.role()).toString());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public List<Map<String, String>> listar() {
        return whitelist.listar().stream()
                .map(e -> {
                    Map<String, String> dto = new HashMap<>();
                    dto.put("id", e.getId().toString());
                    dto.put("email", e.getEmail());
                    dto.put("role", e.getRole().name());
                    if (e.getCreatedAt() != null) dto.put("createdAt", e.getCreatedAt().toString());
                    return dto;
                }).toList();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void remove(@PathVariable UUID id) { whitelist.remove(id); }

    @PostMapping("/requests")
    @PreAuthorize("hasRole('PROFESSOR')")
    public Map<String, String> solicitar(@AuthenticationPrincipal GatewayPrincipal p,
                                         @Valid @RequestBody CreateWhitelistRequest r) {
        return Map.of("id", whitelist.solicitar(p.id(), r.email(), r.reason()).toString());
    }

    /** DEC-29 · the ADMIN/GESTOR review queue: pending + resolved, newest first. */
    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public List<Map<String, String>> listarSolicitudes() {
        return whitelist.listarSolicitudes().stream().map(this::aDto).toList();
    }

    private Map<String, String> aDto(WhitelistRequest r) {
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

    @PatchMapping("/requests/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void resolver(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                         @Valid @RequestBody ResolveWhitelistRequest r) {
        whitelist.resolver(p.id(), id, r.approve(), r.rejectionReason());
    }
}
