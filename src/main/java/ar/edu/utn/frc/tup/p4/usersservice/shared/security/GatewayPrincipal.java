package ar.edu.utn.frc.tup.p4.usersservice.shared.security;

import java.util.UUID;

public record GatewayPrincipal(String principalType, UUID id, String serviceId) {
    public boolean isPerson() { return "user".equals(principalType); }
}
