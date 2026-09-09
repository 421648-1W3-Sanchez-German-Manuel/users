package ar.edu.utn.frc.tup.p4.usersservice.auth.keys;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.impl.FileSystemSigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyProviderTest {

    @TempDir
    static Path dir;

    static Path privateKey;
    static Path jwksDir;

    @BeforeAll
    static void generateKeys() throws IOException, InterruptedException {
        privateKey = dir.resolve("jwt-private.pem");
        jwksDir = Files.createDirectories(dir.resolve("jwks"));
        run("openssl", "genpkey", "-algorithm", "RSA",
                "-pkeyopt", "rsa_keygen_bits:2048", "-out", privateKey.toString());
        run("openssl", "rsa", "-in", privateKey.toString(), "-pubout",
                "-out", jwksDir.resolve("2026-09.pem").toString());
        run("openssl", "genpkey", "-algorithm", "RSA",
                "-pkeyopt", "rsa_keygen_bits:2048", "-out", dir.resolve("old.pem").toString());
        run("openssl", "rsa", "-in", dir.resolve("old.pem").toString(), "-pubout",
                "-out", jwksDir.resolve("2026-03.pem").toString());
    }

    private static void run(String... command) throws IOException, InterruptedException {
        assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
    }

    private JwtProperties properties(String privateKeyPath, String kid) {
        return new JwtProperties("users-service", Duration.ofMinutes(10), Duration.ofDays(7),
                Duration.ofMinutes(5), privateKeyPath, jwksDir.toString(), kid);
    }

    @Test
    void signsWithTheActiveKid() {
        var provider = new FileSystemSigningKeyProvider(properties(privateKey.toString(), "2026-09"));

        assertThat(provider.claveDeFirma().getKeyID()).isEqualTo("2026-09");
        assertThat(provider.claveDeFirma().isPrivate()).isTrue();
    }

    @Test
    void jwksPublishesEveryPublicKeyInTheDirectory() {
        var provider = new FileSystemSigningKeyProvider(properties(privateKey.toString(), "2026-09"));

        assertThat(provider.jwksPublico().getKeys()).extracting(key -> key.getKeyID())
                .containsExactlyInAnyOrder("2026-09", "2026-03");
    }

    @Test
    void jwksDoesNotExposePrivateKeys() {
        var provider = new FileSystemSigningKeyProvider(properties(privateKey.toString(), "2026-09"));

        assertThat(provider.jwksPublico().getKeys()).allMatch(key -> !key.isPrivate());
    }

    @Test
    void applicationDoesNotStartWhenPrivateKeyDoesNotExist() {
        assertThatThrownBy(() -> new FileSystemSigningKeyProvider(
                properties(dir.resolve("missing.pem").toString(), "2026-09")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.pem");
    }

    @Test
    void applicationDoesNotStartWhenActiveKidHasNoPublicKey() {
        assertThatThrownBy(() -> new FileSystemSigningKeyProvider(
                properties(privateKey.toString(), "unknown-kid")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown-kid");
    }
}
