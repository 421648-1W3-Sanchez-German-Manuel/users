package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;

import java.time.Instant;

/** Row of the ADMIN/GESTOR review queue (DEC-29). */
public record WhitelistRequestResponse(String id, String email, String requestedBy, RequestStatus status,
                                       String reason, String rejectionReason, Instant createdAt) { }
