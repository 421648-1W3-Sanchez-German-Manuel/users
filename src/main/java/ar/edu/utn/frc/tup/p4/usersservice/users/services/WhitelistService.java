package ar.edu.utn.frc.tup.p4.usersservice.users.services;

import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.NotificationEventPublisher;
import ar.edu.utn.frc.tup.p4.usersservice.shared.notifications.EmailType;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
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

    private final EmailWhitelistRepository lista;
    private final WhitelistRequestRepository solicitudes;
    private final UserRepository usuarios;
    private final NotificationEventPublisher mails;

    public WhitelistService(EmailWhitelistRepository lista, WhitelistRequestRepository solicitudes,
                            UserRepository usuarios, NotificationEventPublisher mails) {
        this.lista = lista; this.solicitudes = solicitudes;
        this.usuarios = usuarios; this.mails = mails;
    }

    @Transactional
    public UUID agregar(UUID admin, String email) {
        return lista.saveAndFlush(EmailWhitelist.create(email, admin)).getId();
    }

    @Transactional
    public void remove(UUID id) {
        EmailWhitelist e = lista.findById(id).orElseThrow(ApiException::accessDenied);
        e.remove();
        lista.save(e);
    }

    @Transactional(readOnly = true)
    public List<EmailWhitelist> listar() { return lista.findAllByDeletedAtIsNull(); }

    /** DEC-29 · the ADMIN's review queue, newest first. */
    @Transactional(readOnly = true)
    public List<WhitelistRequest> listarSolicitudes() {
        return solicitudes.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Transactional
    public UUID solicitar(UUID profesorId, String email, String reason) {
        // The unique key on pending_email makes the second open request fail.
        WhitelistRequest r = solicitudes.saveAndFlush(
                WhitelistRequest.create(email, profesorId, reason));

        usuarios.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && u.getDeletedAt() == null)
                .forEach(a -> mails.enviar(EmailType.WHITELIST_SUBMISSION, a.getEmail(),
                        Map.of("emailSolicitado", r.getRequestedEmail(), "reason", reason)));
        return r.getId();
    }

    /**
     * DEC-29 · ATOMICO: marcar APPROVED e insertar en la whitelist ocurren en
     * la misma transaccion. No hay estado intermedio donde la request quede
     * aprobada con el email sin estar en la whitelist.
     */
    @Transactional
    public void resolver(UUID adminId, UUID solicitudId, boolean approve, String rejectionReason) {
        WhitelistRequest r = solicitudes.findById(solicitudId).orElseThrow(ApiException::accessDenied);

        if (approve) {
            r.approve(adminId);
            if (!lista.existsByEmailAndDeletedAtIsNull(r.getRequestedEmail())) {
                lista.save(EmailWhitelist.create(r.getRequestedEmail(), adminId));
            }
        } else {
            r.reject(adminId, rejectionReason);
        }
        solicitudes.save(r);

        usuarios.findByIdAndDeletedAtIsNull(r.getRequestedBy()).ifPresent(prof ->
                mails.enviar(EmailType.WHITELIST_DECISION, prof.getEmail(), Map.of(
                        "firstNames", prof.getFirstNames(),
                        "emailSolicitado", r.getRequestedEmail(),
                        "resultado", approve ? RequestStatus.APPROVED.name()
                                             : RequestStatus.REJECTED.name(),
                        "reason", rejectionReason == null ? "" : rejectionReason)));
    }
}
