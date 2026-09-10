package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.WhitelistService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/users/whitelist")
public class WhitelistController {

    private final WhitelistService whitelist;

    public WhitelistController(WhitelistService whitelist) { this.whitelist = whitelist; }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> agregar(@AuthenticationPrincipal GatewayPrincipal p,
                                       @Valid @RequestBody AddEmailRequest r) {
        return Map.of("id", whitelist.agregar(p.id(), r.email()).toString());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, String>> listar() {
        return whitelist.listar().stream()
                .map(e -> Map.of("id", e.getId().toString(), "email", e.getEmail())).toList();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(@PathVariable UUID id) { whitelist.remove(id); }

    @PostMapping("/requests")
    @PreAuthorize("hasRole('PROFESSOR')")
    public Map<String, String> solicitar(@AuthenticationPrincipal GatewayPrincipal p,
                                         @Valid @RequestBody CreateWhitelistRequest r) {
        return Map.of("id", whitelist.solicitar(p.id(), r.email(), r.reason()).toString());
    }

    @PatchMapping("/requests/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void resolver(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                         @Valid @RequestBody ResolveWhitelistRequest r) {
        whitelist.resolver(p.id(), id, r.approve(), r.rejectionReason());
    }
}
