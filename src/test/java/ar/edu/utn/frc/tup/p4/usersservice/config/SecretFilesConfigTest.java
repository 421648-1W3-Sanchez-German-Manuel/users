package ar.edu.utn.frc.tup.p4.usersservice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database password and the initial admin password come from files that the
 * Vault Agent renders (Spring Config Tree over /run/secrets/config/). This runs
 * the real application.yml against a temporary directory, with no database.
 */
class SecretFilesConfigTest {

    @Configuration
    static class Empty { }

    private static ConfigurableApplicationContext start(Path secretsDir) {
        String root = secretsDir.toAbsolutePath().toString().replace('\\', '/') + "/";
        return new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run("--spring.config.import=optional:configtree:" + root);
    }

    @Test
    void passwordsAreReadFromFiles(@TempDir Path dir) throws Exception {
        // A trailing newline is what a hand-written file has; Config Tree trims it.
        Files.writeString(dir.resolve("db-password"), "db-from-file\n");
        Files.writeString(dir.resolve("admin-bootstrap-password"), "admin-from-file");

        try (ConfigurableApplicationContext context = start(dir)) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("db-from-file");
            assertThat(env.getProperty("users.bootstrap.password")).isEqualTo("admin-from-file");
        }
    }

    @Test
    void withoutFilesTheStartupDoesNotFail(@TempDir Path dir) {
        try (ConfigurableApplicationContext context = start(dir.resolve("does-not-exist"))) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.password")).isEmpty();
            assertThat(env.getProperty("users.bootstrap.password")).isEmpty();
        }
    }
}
