package ar.edu.utn.frc.tup.p4.usersservice;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

/**
 * Base class for every integration test.
 *
 * DEC-20 rule 6: the SAME MySQL image as production, never H2 — H2 in "MySQL
 * mode" does not reproduce generated columns, SKIP LOCKED, REPEATABLE READ or
 * the collation.
 *
 * SINGLETON PATTERN, on purpose: the containers start ONCE for the whole JVM
 * and are not stopped at the end of each class. With @Testcontainers +
 * @Container (per-class lifecycle) JUnit stops the container at the end while
 * Spring CACHES the context between classes, so the reused context keeps
 * pointing at a dead port and everything fails with Connection refused — green
 * one at a time, red when run together.
 *
 * MySQL datadir on tmpfs: on slow disks `--initialize` takes 4+ minutes and
 * exceeds the Testcontainers startup window. In RAM it takes ~10s. Same image,
 * same semantics — only the physical medium changes.
 *
 * THE CONSEQUENCE THAT BITES: one database, shared by every test class, with NO
 * cleanup between them. What one class commits, the next one sees.
 *
 * So a fixed e-mail in a fixture is a collision waiting to happen. EmailReuseIT
 * commits `dup@utn.edu.ar` to prove the unique index of DEC-21; any other test
 * that inserts that same address gets a duplicate-key error it did not expect,
 * or — worse — its FIRST insert fails with a 409 that looks like a bug in the
 * code under test. Same for `uq_whitelist_request_pending`.
 *
 * Use a unique address per run in anything you insert:
 *
 *     String email = "alta-" + UUID.randomUUID() + "@utn.edu.ar";
 *
 * And do not "fix" it by adding @Transactional to the test: half of what is
 * being verified here is what the DATABASE does on commit — generated columns,
 * unique indexes, SKIP LOCKED — and a transaction that rolls back never gets
 * there.
 *
 * It registers NO test doubles. An @Import listing the spies of every flow
 * would turn this class — the base of ALL integration tests — into a file five
 * different people edit. Each test declares its own:
 *
 *     @Import(TestOtpSpy.Config.class)
 *     class LoginIT extends AbstractIntegrationTest { ... }
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL;
    protected static final RedisContainer REDIS;

    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("users")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withStartupTimeout(Duration.ofMinutes(3))
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--default-time-zone=+00:00");

        REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

        // They are never stopped: they live as long as the JVM does. Ryuk cleans them up.
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // The test context has no Kafka and the poller would block the thread
        // on kafka.send().get() for minutes, holding the outbox_events lock
        // (FOR UPDATE SKIP LOCKED) and timing out the test's own inserts. The
        // real publish is verified in its own IT.
        r.add("users.outbox.poller-enabled", () -> "false");
        // Same reasoning as the poller: with no broker there is no container to
        // listen. The listener is tested by calling the method directly.
        r.add("users.kafka.listener-auto-startup", () -> "false");
    }
}
