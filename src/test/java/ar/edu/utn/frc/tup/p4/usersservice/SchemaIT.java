package ar.edu.utn.frc.tup.p4.usersservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void every_migration_ran() {
        List<String> tablas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tablas).contains("users", "email_whitelist", "service_clients",
                "service_client_scopes", "processed_events", "whitelist_requests", "outbox_events");
    }

    @Test
    void every_table_is_utf8mb4() {
        // DEC-20 rule 4: plain utf8 is 3 bytes and misses the supplementary plane.
        List<String> noUtf8mb4 = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_collation NOT LIKE 'utf8mb4%'",
                String.class);
        assertThat(noUtf8mb4).isEmpty();
    }

    @Test
    void ninguna_columna_es_TIMESTAMP() {
        // DEC-20 rule 2: TIMESTAMP converts using the session timezone and dies in 2038.
        List<String> malas = jdbc.queryForList(
                "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND data_type = 'timestamp' " +
                "AND table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(malas).isEmpty();
    }

    @Test
    void todos_los_ids_son_CHAR_36() {
        // DEC-20 rule 1
        List<String> malas = jdbc.queryForList(
                "SELECT CONCAT(table_name,'.',column_name) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND column_name = 'id' " +
                "AND NOT (data_type = 'char' AND character_maximum_length = 36)",
                String.class);
        assertThat(malas).isEmpty();
    }
}
