package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.RequestStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.*;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.WhitelistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEC-29 · request con ESTADO, no un mail suelto. */
class WhitelistRequestIT extends AbstractIntegrationTest {

    @Autowired WhitelistService whitelist;
    @Autowired WhitelistRequestRepository solicitudes;
    @Autowired EmailWhitelistRepository lista;
    @Autowired UserRepository repo;

    private UUID profesor() { return crear(Role.PROFESSOR, "prof-" + UUID.randomUUID() + "@utn.edu.ar"); }
    private UUID adminId()  { return crear(Role.ADMIN,    "adm-"  + UUID.randomUUID() + "@utn.edu.ar"); }

    private UUID crear(Role role, String email) {
        User u = User.create("N", "A", email, "$2a$12$h", role, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void aprobar_marca_APROBADA_e_inserta_en_la_whitelist_en_la_MISMA_transaccion() {
        UUID sol = whitelist.solicitar(profesor(), "nuevo1@utn.edu.ar", "es titular de la catedra");
        whitelist.resolver(adminId(), sol, true, null);

        assertThat(solicitudes.findById(sol)).get()
                .extracting(s -> s.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(lista.existsByEmailAndDeletedAtIsNull("nuevo1@utn.edu.ar")).isTrue();
    }

    @Test
    void rechazar_exige_motivo_y_NO_toca_la_whitelist() {
        UUID sol = whitelist.solicitar(profesor(), "nuevo2@utn.edu.ar", "reason");
        assertThatThrownBy(() -> whitelist.resolver(adminId(), sol, false, null))
                .isInstanceOf(RuntimeException.class);

        whitelist.resolver(adminId(), sol, false, "no corresponde");
        assertThat(lista.existsByEmailAndDeletedAtIsNull("nuevo2@utn.edu.ar")).isFalse();
        // RF-NFR-01: a rejected request STAYS, with its reason. With a mail
        // loose, nobody could answer "what happened to what I asked for".
        assertThat(solicitudes.findById(sol)).get()
                .extracting(s -> s.getStatus()).isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void dos_profesores_no_pueden_abrir_dos_solicitudes_para_el_mismo_email() {
        whitelist.solicitar(profesor(), "duplicado@utn.edu.ar", "uno");
        assertThatThrownBy(() -> whitelist.solicitar(profesor(), "duplicado@utn.edu.ar", "dos"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void tras_rechazar_se_puede_volver_a_solicitar_el_mismo_email() {
        // The unique key applies only among the PENDING ones (generated column).
        UUID sol = whitelist.solicitar(profesor(), "reintento@utn.edu.ar", "uno");
        whitelist.resolver(adminId(), sol, false, "no");
        assertThat(whitelist.solicitar(profesor(), "reintento@utn.edu.ar", "dos")).isNotNull();
    }

    @Test
    void resolver_dos_veces_la_misma_solicitud_falla() {
        UUID sol = whitelist.solicitar(profesor(), "unavez@utn.edu.ar", "reason");
        whitelist.resolver(adminId(), sol, true, null);
        assertThatThrownBy(() -> whitelist.resolver(adminId(), sol, true, null))
                .isInstanceOf(RuntimeException.class);
    }
}
