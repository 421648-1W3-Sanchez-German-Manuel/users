package ar.edu.utn.frc.tup.p4.usersservice.shared.audit;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.auth.entities.ServiceClient;
import ar.edu.utn.frc.tup.p4.usersservice.auth.repositories.ServiceClientRepository;
import ar.edu.utn.frc.tup.p4.usersservice.shared.security.GatewayPrincipal;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.EmailWhitelist;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.User;
import ar.edu.utn.frc.tup.p4.usersservice.users.entities.WhitelistRequest;
import ar.edu.utn.frc.tup.p4.usersservice.users.enums.Role;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.EmailWhitelistRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.UserRepository;
import ar.edu.utn.frc.tup.p4.usersservice.users.repositories.WhitelistRequestRepository;
import org.hibernate.HibernateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditIT extends AbstractIntegrationTest {

    @Autowired UserRepository users;
    @Autowired EmailWhitelistRepository whitelist;
    @Autowired ServiceClientRepository serviceClients;
    @Autowired WhitelistRequestRepository whitelistRequests;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void clearThreadContext() {
        SecurityContextHolder.clearContext();
        MDC.remove("traceId");
    }

    @Test
    void insert_sets_common_fields_without_creating_history() {
        UUID actor = UUID.randomUUID();
        String traceId = "0123456789abcdef0123456789abcdef";
        authenticate(new GatewayPrincipal("user", actor, null));
        MDC.put("traceId", traceId);

        User user = users.saveAndFlush(newUser(Role.PROFESSOR));

        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getCreatedUser()).isEqualTo(actor);
        assertThat(user.getLastUpdatedUser()).isEqualTo(actor);
        assertThat(user.getCreatedTraceId()).isEqualTo(traceId);
        assertThat(user.getLastUpdatedTraceId()).isEqualTo(traceId);
        assertThat(user.getLockVersion()).isZero();
        assertThat(auditCount("users_audit", user.getId())).isZero();
    }

    @Test
    void update_archives_previous_state_only_once_per_transaction() {
        User user = users.saveAndFlush(newUser(Role.PROFESSOR));

        transactions.executeWithoutResult(status -> {
            User managed = users.findById(user.getId()).orElseThrow();
            managed.changeRole(Role.STUDENT);
            users.saveAndFlush(managed);
            managed.changeRole(Role.PROFESSOR);
            users.saveAndFlush(managed);
        });

        assertThat(auditCount("users_audit", user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT role FROM users_audit WHERE id = ?",
                String.class,
                user.getId().toString())).isEqualTo("PROFESSOR");
        assertThat(jdbc.queryForObject(
                "SELECT lock_version FROM users_audit WHERE id = ?",
                Long.class,
                user.getId().toString())).isZero();
    }

    @Test
    void soft_delete_archives_the_active_state() {
        User user = users.saveAndFlush(newUser(Role.PROFESSOR));

        transactions.executeWithoutResult(status -> {
            User managed = users.findById(user.getId()).orElseThrow();
            managed.deactivate();
            users.saveAndFlush(managed);
        });

        assertThat(users.findById(user.getId())).get()
                .extracting(User::getDeletedAt).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM users_audit WHERE id = ?",
                Boolean.class,
                user.getId().toString())).isTrue();
    }

    @Test
    void whitelist_and_request_updates_archive_the_previous_state() {
        UUID actor = UUID.randomUUID();
        EmailWhitelist entry = whitelist.saveAndFlush(
                EmailWhitelist.create(uniqueEmail("whitelist"), actor));
        WhitelistRequest request = whitelistRequests.saveAndFlush(
                WhitelistRequest.create(uniqueEmail("request"), actor, "Course access"));

        transactions.executeWithoutResult(status -> {
            EmailWhitelist managedEntry = whitelist.findById(entry.getId()).orElseThrow();
            WhitelistRequest managedRequest =
                    whitelistRequests.findById(request.getId()).orElseThrow();
            managedEntry.remove();
            whitelist.saveAndFlush(managedEntry);
            managedRequest.approve(actor);
            whitelistRequests.saveAndFlush(managedRequest);
        });

        assertThat(auditCount("email_whitelist_audit", entry.getId())).isEqualTo(1);
        assertThat(auditCount("whitelist_requests_audit", request.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM whitelist_requests_audit WHERE id = ?",
                String.class,
                request.getId().toString())).isEqualTo("PENDING");
    }

    @Test
    void service_operations_store_the_service_actor() {
        authenticate(new GatewayPrincipal("service", null, "test-service"));
        ServiceClient client = serviceClients.saveAndFlush(ServiceClient.create(
                "client-" + UUID.randomUUID(),
                passwordEncoder.encode("secret-value"),
                "Audit test",
                Set.of("users:read")));

        assertThat(client.getCreatedUser()).isNull();
        assertThat(client.getLastUpdatedUser()).isNull();
        assertThat(client.getCreatedService()).isEqualTo("test-service");
        assertThat(client.getLastUpdatedService()).isEqualTo("test-service");
        assertThat(auditCount("service_clients_audit", client.getId())).isZero();
    }

    @Test
    void service_actor_is_preserved_in_history_and_replaced_by_person_actor() {
        String creationTraceId = "0123456789abcdef0123456789abcdef";
        String updateTraceId = "fedcba9876543210fedcba9876543210";
        authenticate(new GatewayPrincipal("service", null, "test-service"));
        MDC.put("traceId", creationTraceId);
        User user = users.saveAndFlush(newUser(Role.PROFESSOR));

        UUID personActor = UUID.randomUUID();
        authenticate(new GatewayPrincipal("user", personActor, null));
        MDC.put("traceId", updateTraceId);
        transactions.executeWithoutResult(status -> {
            User managed = users.findById(user.getId()).orElseThrow();
            managed.changeRole(Role.STUDENT);
            users.saveAndFlush(managed);
        });

        User updated = users.findById(user.getId()).orElseThrow();
        assertThat(updated.getCreatedService()).isEqualTo("test-service");
        assertThat(updated.getLastUpdatedService()).isNull();
        assertThat(updated.getLastUpdatedUser()).isEqualTo(personActor);
        assertThat(updated.getCreatedTraceId()).isEqualTo(creationTraceId);
        assertThat(updated.getLastUpdatedTraceId()).isEqualTo(updateTraceId);
        assertThat(jdbc.queryForObject(
                "SELECT created_service FROM users_audit WHERE id = ?",
                String.class,
                user.getId().toString())).isEqualTo("test-service");
        assertThat(jdbc.queryForObject(
                "SELECT last_updated_service FROM users_audit WHERE id = ?",
                String.class,
                user.getId().toString())).isEqualTo("test-service");
        assertThat(jdbc.queryForObject(
                "SELECT created_trace_id FROM users_audit WHERE id = ?",
                String.class,
                user.getId().toString())).isEqualTo(creationTraceId);
        assertThat(jdbc.queryForObject(
                "SELECT last_updated_trace_id FROM users_audit WHERE id = ?",
                String.class,
                user.getId().toString())).isEqualTo(creationTraceId);
    }

    @Test
    void physical_delete_is_rejected() {
        User user = users.saveAndFlush(newUser(Role.PROFESSOR));

        assertThatThrownBy(() -> users.delete(user))
                .hasRootCauseInstanceOf(HibernateException.class)
                .hasRootCauseMessage(
                        "Physical deletion is forbidden for audited entity "
                                + User.class.getName());
        assertThat(users.findById(user.getId())).isPresent();
    }

    private User newUser(Role role) {
        return User.create(
                "Audit",
                "Test",
                uniqueEmail("user"),
                passwordEncoder.encode("password-value"),
                role,
                "v1");
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@utn.edu.ar";
    }

    private long auditCount(String table, UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?",
                Long.class,
                id.toString());
        return count == null ? 0 : count;
    }

    private void authenticate(GatewayPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
