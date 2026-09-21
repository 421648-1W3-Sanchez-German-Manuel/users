package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.WhitelistEntryResponse;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.WhitelistRequestResponse;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WhitelistService {

    private final EmailWhitelistRepository whitelist;
    private final WhitelistRequestRepository requests;
    private final UserRepository users;
    private final NotificationEventPublisher mails;

    public WhitelistService(EmailWhitelistRepository whitelist, WhitelistRequestRepository requests,
                            UserRepository users, NotificationEventPublisher mails) {
        this.whitelist = whitelist; this.requests = requests;
        this.users = users; this.mails = mails;
    }

    /**
     * {@code rawRole} stays a raw string here, not the enum, for the same reason
     * it stays raw in {@link ar.edu.utn.frc.tup.p4.usersservice.users.dto.AddEmailRequest}:
     * an invalid enum value at the HTTP boundary would fail Jackson deserialization
     * before Bean Validation runs, producing a generic 400 instead of this explicit
     * message. Parsing and validating it here — not in the controller — means the
     * PROFESSOR/GESTOR restriction is enforced for every caller of this use case,
     * HTTP or not.
     */
    @Transactional
    public UUID add(UUID actor, String email, String rawRole) {
        Role effectiveRole = Role.PROFESSOR;
        if (rawRole != null && !rawRole.isBlank()) {
            try {
                effectiveRole = Role.valueOf(rawRole);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Invalid role: '" + rawRole + "'. Must be PROFESSOR or GESTOR.");
            }
        }
        if (effectiveRole != Role.PROFESSOR && effectiveRole != Role.GESTOR) {
            throw ApiException.validation("The whitelist accepts only PROFESSOR or GESTOR.");
        }
        String normalized = email.toLowerCase(Locale.ROOT);
        if (whitelist.existsByEmailAndDeletedAtIsNull(normalized)) {
            throw ApiException.duplicateEmail();
        }
        return whitelist.saveAndFlush(EmailWhitelist.create(normalized, effectiveRole, actor)).getId();
    }

    @Transactional
    public void remove(UUID id) {
        EmailWhitelist e = whitelist.findById(id).orElseThrow(ApiException::accessDenied);
        e.remove();
        whitelist.save(e);
    }

    @Transactional(readOnly = true)
    public List<WhitelistEntryResponse> list() {
        return whitelist.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .map(e -> new WhitelistEntryResponse(e.getId().toString(), e.getEmail(), e.getRole(),
                        e.getCreatedAt()))
                .toList();
    }

    /** DEC-29 · the ADMIN's review queue, newest first. */
    @Transactional(readOnly = true)
    public List<WhitelistRequestResponse> listRequests() {
        return requests.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(r -> new WhitelistRequestResponse(r.getId().toString(), r.getRequestedEmail(),
                        r.getRequestedBy().toString(), r.getStatus(),
                        r.getReason() == null ? "" : r.getReason(),
                        r.getRejectionReason() == null ? "" : r.getRejectionReason(),
                        r.getCreatedAt()))
                .toList();
    }

    @Transactional
    public UUID request(UUID professorId, String email, String reason) {
        // The unique key on pending_email makes the second open request fail.
        WhitelistRequest r = requests.saveAndFlush(
                WhitelistRequest.create(email, professorId, reason));

        // GESTOR now attends the review queue too, so it must hear about new requests as well.
        users.findAll().stream()
                .filter(u -> (u.getRole() == Role.ADMIN || u.getRole() == Role.GESTOR) && u.getDeletedAt() == null)
                .forEach(a -> mails.send(
                        EmailType.WHITELIST_SUBMISSION,
                        a.getId(),
                        a.getEmail(),
                        Map.of("emailSolicitado", r.getRequestedEmail(), "reason", reason)));
        return r.getId();
    }

    /**
     * DEC-29 - ATOMIC: marking APPROVED and inserting into the whitelist occur
     * in the same transaction. There is no intermediate state where the request
     * is approved but the email is absent from the whitelist.
     */
    @Transactional
    public void resolve(UUID adminId, UUID requestId, boolean approve, String rejectionReason) {
        WhitelistRequest r = requests.findById(requestId).orElseThrow(ApiException::accessDenied);

        if (approve) {
            // request() is PROFESSOR-only (a colleague referral), so the role is always PROFESSOR.
            // The active_email unique key means an email can only be whitelisted for one role at
            // a time: if it's already active under GESTOR (or another role), approving here would
            // mark the request APPROVED without ever inserting a usable PROFESSOR row.
            boolean alreadyProfessor = whitelist.existsByEmailAndRoleAndDeletedAtIsNull(
                    r.getRequestedEmail(), Role.PROFESSOR);
            if (!alreadyProfessor && whitelist.existsByEmailAndDeletedAtIsNull(r.getRequestedEmail())) {
                throw ApiException.validation(
                        "The email is already authorized under another role. Remove it from the whitelist before approving this request.");
            }
            r.approve(adminId);
            if (!alreadyProfessor) {
                whitelist.save(EmailWhitelist.create(r.getRequestedEmail(), Role.PROFESSOR, adminId));
            }
        } else {
            r.reject(adminId, rejectionReason);
        }
        requests.save(r);

        users.findByIdAndDeletedAtIsNull(r.getRequestedBy()).ifPresent(professor ->
                mails.send(EmailType.WHITELIST_DECISION, professor.getId(), professor.getEmail(), Map.of(
                        "firstNames", professor.getFirstNames(),
                        "emailSolicitado", r.getRequestedEmail(),
                        "resultado", approve ? RequestStatus.APPROVED.name()
                                             : RequestStatus.REJECTED.name(),
                        "reason", rejectionReason == null ? "" : rejectionReason)));
    }
}
