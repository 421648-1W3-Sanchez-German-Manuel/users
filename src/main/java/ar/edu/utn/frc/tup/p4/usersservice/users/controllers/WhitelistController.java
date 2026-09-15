package ar.edu.utn.frc.tup.p4.usersservice.users.controllers;

import ar.edu.utn.frc.tup.p4.usersservice.config.OpenApiConfig;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.WhitelistRequest;
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
             Emails habilitados para registrarse como profesor, y la cola de solicitudes
             que un ADMIN revisa.""")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("${app.api.private-path}/whitelist")
public class WhitelistController {

    private final WhitelistService whitelist;

    public WhitelistController(WhitelistService whitelist) { this.whitelist = whitelist; }

    @Operation(summary = "Habilita un email (ADMIN)",
               description = """
                       Alta directa, sin pasar por la cola de solicitudes. A partir de aca ese
                       email puede auto-registrarse como profesor.""")
    @ApiResponse(responseCode = "200", description = "Habilitado. Devuelve el `id`.")
    @ApiResponse(responseCode = "409", description = "`type`: `duplicate-email`. Ya estaba habilitado.")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public Map<String, String> agregar(@AuthenticationPrincipal GatewayPrincipal p,
                                       @Valid @RequestBody AddEmailRequest r) {
        return Map.of("id", whitelist.agregar(p.id(), r.email(), r.role()).toString());
    }

    @Operation(summary = "Lista los emails habilitados (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Cada fila trae `id`, `email` y `createdAt`.")
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

    @Operation(summary = "Quita un email de la whitelist (ADMIN)",
               description = """
                       No afecta a las cuentas YA creadas con ese email: saca la habilitacion
                       para registrarse, no da de baja a nadie.""")
    @ApiResponse(responseCode = "200", description = "Quitado.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void remove(@PathVariable UUID id) { whitelist.remove(id); }

    @Operation(summary = "Solicita habilitar un email (PROFESSOR)",
               description = """
                       El camino del profesor que quiere sumar a alguien pero no puede
                       habilitarlo por su cuenta: deja la solicitud y un ADMIN la resuelve.

                       Solo puede haber UNA solicitud pendiente por email.""")
    @ApiResponse(responseCode = "200", description = "Solicitud creada. Devuelve el `id`.")
    @ApiResponse(responseCode = "409", description = """
            `type`: `duplicate-email`. Ya hay una solicitud pendiente para ese email.""")
    @PostMapping("/requests")
    @PreAuthorize("hasRole('PROFESSOR')")
    public Map<String, String> solicitar(@AuthenticationPrincipal GatewayPrincipal p,
                                         @Valid @RequestBody CreateWhitelistRequest r) {
        return Map.of("id", whitelist.solicitar(p.id(), r.email(), r.reason()).toString());
    }

    /** DEC-29 · the ADMIN/GESTOR review queue: pending + resolved, newest first. */
    @Operation(summary = "Cola de revision de solicitudes (ADMIN/GESTOR)",
               description = """
                       Pendientes Y resueltas, de la mas nueva a la mas vieja. Las resueltas
                       quedan a la vista a proposito: sin ellas no hay forma de ver que se
                       rechazo ni por que.""")
    @ApiResponse(responseCode = "200", description = """
            Cada fila trae `id`, `email`, `requestedBy`, `status`, `reason`, `rejectionReason`
            y `createdAt`.""")
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

    @Operation(summary = "Aprueba o rechaza una solicitud (ADMIN)",
               description = """
                       Aprobarla habilita el email. `rejectionReason` es obligatorio cuando
                       `approve` es `false`: un rechazo sin motivo no le sirve a nadie.""")
    @ApiResponse(responseCode = "200", description = "Solicitud resuelta.")
    @ApiResponse(responseCode = "400", description = """
            `type`: `validation`. Rechazo sin `rejectionReason`, o una solicitud que ya
            estaba resuelta.""")
    @PatchMapping("/requests/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GESTOR')")
    public void resolver(@AuthenticationPrincipal GatewayPrincipal p, @PathVariable UUID id,
                         @Valid @RequestBody ResolveWhitelistRequest r) {
        whitelist.resolver(p.id(), id, r.approve(), r.rejectionReason());
    }
}
