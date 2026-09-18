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

/** DEC-29: a request with STATUS, not a standalone email. */
class WhitelistRequestIT extends AbstractIntegrationTest {

    @Autowired WhitelistService whitelist;
    @Autowired WhitelistRequestRepository requests;
    @Autowired EmailWhitelistRepository entries;
    @Autowired UserRepository repo;

    private UUID professorId() { return createActive(Role.PROFESSOR, "prof-" + UUID.randomUUID() + "@utn.edu.ar"); }
    private UUID adminId()  { return createActive(Role.ADMIN, "adm-" + UUID.randomUUID() + "@utn.edu.ar"); }

    private UUID createActive(Role role, String email) {
        User u = User.create("N", "A", email, "$2a$12$h", role, "v1");
        u.forceStatusForTest(AccountStatus.ACTIVE);
        return repo.saveAndFlush(u).getId();
    }

    @Test
    void approval_marks_APPROVED_and_inserts_into_the_whitelist_in_the_SAME_transaction() {
        UUID requestId = whitelist.request(professorId(), "new1@utn.edu.ar", "is the course chair");
        whitelist.resolve(adminId(), requestId, true, null);

        assertThat(requests.findById(requestId)).get()
                .extracting(s -> s.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(entries.existsByEmailAndDeletedAtIsNull("new1@utn.edu.ar")).isTrue();
    }

    @Test
    void rejection_requires_a_reason_and_does_NOT_modify_the_whitelist() {
        UUID requestId = whitelist.request(professorId(), "new2@utn.edu.ar", "reason");
        assertThatThrownBy(() -> whitelist.resolve(adminId(), requestId, false, null))
                .isInstanceOf(RuntimeException.class);

        whitelist.resolve(adminId(), requestId, false, "not applicable");
        assertThat(entries.existsByEmailAndDeletedAtIsNull("new2@utn.edu.ar")).isFalse();
        // RF-NFR-01: a rejected request STAYS, with its reason. A standalone
        // email could not answer "what happened to my request?".
        assertThat(requests.findById(requestId)).get()
                .extracting(s -> s.getStatus()).isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void two_professors_cannot_open_two_requests_for_the_same_email() {
        whitelist.request(professorId(), "duplicate@utn.edu.ar", "one");
        assertThatThrownBy(() -> whitelist.request(professorId(), "duplicate@utn.edu.ar", "two"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void the_same_email_can_be_requested_again_after_rejection() {
        // The unique key applies only among the PENDING ones (generated column).
        UUID requestId = whitelist.request(professorId(), "retry@utn.edu.ar", "one");
        whitelist.resolve(adminId(), requestId, false, "rejected");
        assertThat(whitelist.request(professorId(), "retry@utn.edu.ar", "two")).isNotNull();
    }

    @Test
    void resolving_the_same_request_twice_fails() {
        UUID requestId = whitelist.request(professorId(), "once@utn.edu.ar", "reason");
        whitelist.resolve(adminId(), requestId, true, null);
        assertThatThrownBy(() -> whitelist.resolve(adminId(), requestId, true, null))
                .isInstanceOf(RuntimeException.class);
    }
}
