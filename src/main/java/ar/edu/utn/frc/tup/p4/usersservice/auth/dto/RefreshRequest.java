package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * El jti es un UUID: aceptar cualquier String dejaba que el body eligiera con
 * que clave de Redis se iba a consultar. Validarlo aca convierte una entrada
 * malformada en un 400 con type, antes de tocar el store.
 */
public record RefreshRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                 message = "refreshToken tiene que ser un UUID")
        String refreshToken) { }
