package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.web.ApiException;
import ar.edu.utn.frc.tup.p4.usersservice.users.dto.AdminDeactivationRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.services.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OJO: esta clase VACIA la tabla `users`, y es el unico lugar del suite que lo
 * hace. La regla que prueba es global (countActiveWithLock(ADMIN) <= 1), asi
 * que no alcanza con emails unicos: la tabla tiene que tener exactamente los
 * ADMIN que este test crea.
 *
 * La base de datos es UNA, compartida por todas las clases de test y sin
 * limpieza entre ellas. Si tu clase necesita que sus filas sobrevivan a otra
 * clase, no lo va a lograr: no dependas del orden.
 */
class AdminRulesIT extends AbstractIntegrationTest {

    @Autowired UserService users;
    @Autowired UserRepository repo;
    @Autowired PasswordEncoder encoder;

    private User admin(String email) {
        User u = User.createAdmin("Ad", "Min", email, encoder.encode("passwordvalida1"), "v1");
        u.changePassword(encoder.encode("passwordvalida1"));   // clears mustChangePassword
        return repo.saveAndFlush(u);
    }

    private User conRol(Role role, String email) {
        User u = User.create("Nom", "Bre", email, encoder.encode("passwordvalida1"), role, "v1");
        return repo.saveAndFlush(u);
    }

    private AdminDeactivationRequest confirmacion(String username) {
        return new AdminDeactivationRequest("passwordvalida1", "123456", username);
    }

    @Test
    void no_se_puede_dejar_la_plataforma_sin_ningun_ADMIN() {
        repo.deleteAll();
        User unico = admin("solo@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(unico.getId(), unico.getId(),
                confirmacion("solo@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_ADMIN_no_puede_darse_de_baja_a_si_mismo() {
        admin("otro@utn.edu.ar");
        User a = admin("auto@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(a.getId(), a.getId(), confirmacion("auto@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void la_baja_de_ADMIN_exige_confirmacion_escrita_del_username() {
        User actor = admin("act@utn.edu.ar");
        User objetivo = admin("obj@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(actor.getId(), objetivo.getId(),
                confirmacion("escrito-mal@utn.edu.ar")))
                .isInstanceOf(ApiException.class);

        users.deactivate(actor.getId(), objetivo.getId(), confirmacion("obj@utn.edu.ar"));
        assertThat(repo.findById(objetivo.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void dos_bajas_CONCURRENTES_no_pueden_dejar_cero_ADMIN() throws Exception {
        repo.deleteAll();
        User a = admin("c1@utn.edu.ar");
        User b = admin("c2@utn.edu.ar");
        User actor = admin("c3@utn.edu.ar");

        var pool = Executors.newFixedThreadPool(2);
        var listos = new CountDownLatch(2);
        var arrancar = new CountDownLatch(1);
        AtomicInteger exitos = new AtomicInteger();

        for (User objetivo : List.of(a, b)) {
            pool.submit(() -> {
                listos.countDown();
                try {
                    arrancar.await();
                    users.deactivate(actor.getId(), objetivo.getId(),
                            confirmacion(objetivo.getEmail()));
                    exitos.incrementAndGet();
                } catch (Exception ignored) { }
            });
        }
        listos.await();
        arrancar.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(repo.countByRoleAndDeletedAtIsNull(Role.ADMIN)).isGreaterThanOrEqualTo(1);
        assertThat(exitos.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void cambiar_el_rol_del_ultimo_ADMIN_tambien_se_bloquea() {
        repo.deleteAll();
        User unico = admin("role@utn.edu.ar");
        assertThatThrownBy(() -> users.changeRole(unico.getId(), unico.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_GESTOR_no_puede_dar_de_baja_a_un_ADMIN() {
        User gestor = conRol(Role.GESTOR, "gestor-baja-admin@utn.edu.ar");
        User objetivo = admin("obj-baja-admin@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(gestor.getId(), objetivo.getId(),
                confirmacion("obj-baja-admin@utn.edu.ar")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_GESTOR_no_puede_dar_de_baja_a_un_STUDENT() {
        User gestor = conRol(Role.GESTOR, "gestor-baja-student@utn.edu.ar");
        User objetivo = conRol(Role.STUDENT, "obj-baja-student@utn.edu.ar");
        assertThatThrownBy(() -> users.deactivate(gestor.getId(), objetivo.getId(),
                new AdminDeactivationRequest("na", "na", "na")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_GESTOR_puede_dar_de_baja_a_un_PROFESSOR_o_GESTOR() {
        User gestor = conRol(Role.GESTOR, "gestor-baja-ok@utn.edu.ar");
        User profesor = conRol(Role.PROFESSOR, "obj-baja-prof@utn.edu.ar");
        User otroGestor = conRol(Role.GESTOR, "obj-baja-gestor@utn.edu.ar");

        users.deactivate(gestor.getId(), profesor.getId(), new AdminDeactivationRequest("na", "na", "na"));
        users.deactivate(gestor.getId(), otroGestor.getId(), new AdminDeactivationRequest("na", "na", "na"));

        assertThat(repo.findById(profesor.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.DEACTIVATED);
        assertThat(repo.findById(otroGestor.getId()))
                .get().extracting(User::getAccountStatus).isEqualTo(AccountStatus.DEACTIVATED);
    }

    @Test
    void un_GESTOR_no_puede_otorgar_ni_tocar_el_rol_ADMIN() {
        User gestor = conRol(Role.GESTOR, "gestor-rol-admin@utn.edu.ar");
        User admin = admin("obj-rol-admin@utn.edu.ar");
        User profesor = conRol(Role.PROFESSOR, "obj-rol-a-admin@utn.edu.ar");

        assertThatThrownBy(() -> users.changeRole(gestor.getId(), admin.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> users.changeRole(gestor.getId(), profesor.getId(), Role.ADMIN))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_GESTOR_no_puede_cambiar_el_rol_de_un_STUDENT() {
        User gestor = conRol(Role.GESTOR, "gestor-rol-student@utn.edu.ar");
        User student = conRol(Role.STUDENT, "obj-rol-student@utn.edu.ar");

        assertThatThrownBy(() -> users.changeRole(gestor.getId(), student.getId(), Role.PROFESSOR))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void un_GESTOR_puede_mover_cuentas_entre_PROFESSOR_y_GESTOR() {
        User gestor = conRol(Role.GESTOR, "gestor-rol-ok@utn.edu.ar");
        User profesor = conRol(Role.PROFESSOR, "obj-rol-ok@utn.edu.ar");

        users.changeRole(gestor.getId(), profesor.getId(), Role.GESTOR);

        assertThat(repo.findById(profesor.getId()))
                .get().extracting(User::getRole).isEqualTo(Role.GESTOR);
    }
}
