package ar.edu.utn.frc.tup.p4.usersservice.shared.events;

import ar.edu.utn.frc.tup.p4.usersservice.AbstractIntegrationTest;
import ar.edu.utn.frc.tup.p4.usersservice.shared.events.entities.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

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
        // Topico unico por corrida, y se filtra por el. La base de datos es UNA
        // para todas las clases de test y nadie limpia entre ellas: en cuanto
        // otro flujo publica un evento -- el mail de 2FA del login, el de
        // activacion del alta -- el outbox deja de tener una sola fila y este
        // test falla por algo que no tiene nada que ver con lo que prueba.
        //
        // Es el mismo patron que ya usa el segundo test de esta clase, que mide
        // un delta contra `outbox.count()` en vez de afirmar sobre la tabla
        // entera.
        String topico = "topico.test." + UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        publisher.publish(
                topico,
                aggregateId.toString(),
                "TEST-EVENT",
                1,
                "test-aggregate",
                aggregateId,
                new Payload("valor"));

        var pendientes = outbox.findAll().stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .filter(event -> topico.equals(event.getDestinationTopic()))
                .toList();

        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.getFirst().getDestinationTopic()).isEqualTo(topico);
        assertThat(pendientes.getFirst().getMessageKey()).isEqualTo(aggregateId.toString());
        assertThat(pendientes.getFirst().getPayload()).contains("\"eventType\":\"TEST-EVENT\"");
        assertThat(pendientes.getFirst().getPayload()).contains("\"eventVersion\":1");
        assertThat(pendientes.getFirst().getPayload()).contains("\"producer\":\"tema-01-users\"");
    }

    @Test
    void si_la_transaccion_hace_rollback_el_evento_no_existe() {
        long antes = outbox.count();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID aggregateId = UUID.randomUUID();
            publisher.publish(
                    "test-events",
                    aggregateId.toString(),
                    "TEST-EVENT",
                    1,
                    "test-aggregate",
                    aggregateId,
                    new Payload("x"));
            throw new IllegalStateException("intentional rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.count()).isEqualTo(antes);
    }

    record Payload(String campo) {
    }
}
