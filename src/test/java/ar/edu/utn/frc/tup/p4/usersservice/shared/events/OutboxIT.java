package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-45b. Kafka does not take part in the MySQL transaction.
 */
class OutboxIT extends AbstractIntegrationTest {

    @Autowired AccountEventPublisher publisher;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;

    @Test
    @Transactional
    void publicar_escribe_una_fila_pendiente_no_publica_a_kafka() {
        publisher.publicar("topico.test.v1", "EVENTO_TEST", new Payload("valor"));

        var pendientes = outbox.findAll().stream()
                .filter(event -> event.getPublishedAt() == null)
                .toList();

        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.getFirst().getTopic()).isEqualTo("topico.test.v1");
        assertThat(pendientes.getFirst().getPayload()).contains("\"eventType\":\"EVENTO_TEST\"");
        assertThat(pendientes.getFirst().getPayload()).contains("\"producer\":\"tema-01-users\"");
    }

    @Test
    void si_la_transaccion_hace_rollback_el_evento_no_existe() {
        long antes = outbox.count();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publisher.publicar("topico.test.v1", "EVENTO_TEST", new Payload("x"));
            throw new IllegalStateException("intentional rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.count()).isEqualTo(antes);
    }

    record Payload(String campo) {
    }
}
