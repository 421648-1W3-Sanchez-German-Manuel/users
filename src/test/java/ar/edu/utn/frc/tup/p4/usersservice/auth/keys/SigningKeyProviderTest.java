package ar.edu.utn.frc.tup.p4.usersservice.auth.keys;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.impl.FileSystemSigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyProviderTest {

    @TempDir
    static Path dir;

    static Path privateKey;
    static Path jwksDir;

    /**
     * The keys are generated with the JDK, NOT by invoking `openssl`.
     *
     * With ProcessBuilder this failed with "CreateProcess error=2" on any
     * Windows system without openssl on the PATH. Openssl is not on the Windows
     * PATH by default; it comes with Git but in its own bin directory. A unit
     * test that depends on an external binary tests the machine, not the code.
     *
     * The format is the same one openssl writes and
     * `JWK.parseFromPEMEncodedObjects` expects: getEncoded() returns PKCS#8 for
     * a private key and SPKI for a public key, exactly the two standard PEM
     * formats.
     */
    @BeforeAll
    static void generateKeys() throws Exception {
        privateKey = dir.resolve("jwt-private.pem");
        jwksDir = Files.createDirectories(dir.resolve("jwks"));

        KeyPair active = rsa();
        writePem(privateKey, "PRIVATE KEY", active.getPrivate().getEncoded());
        writePem(jwksDir.resolve("2026-09.pem"), "PUBLIC KEY", active.getPublic().getEncoded());

        // A second, public-only key no longer signs but remains published in
        // the JWKS so in-flight tokens remain valid during rotation (DEC-18).
        KeyPair rotated = rsa();
        writePem(dir.resolve("old.pem"), "PRIVATE KEY", rotated.getPrivate().getEncoded());
        writePem(jwksDir.resolve("2026-03.pem"), "PUBLIC KEY", rotated.getPublic().getEncoded());
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, System.lineSeparator().getBytes())
                .encodeToString(der);
        Files.writeString(path, "-----BEGIN " + label + "-----" + System.lineSeparator()
                + body + System.lineSeparator()
                + "-----END " + label + "-----" + System.lineSeparator());
    }

    private JwtProperties properties(String privateKeyPath, String kid) {
        return new JwtProperties("users-service", Duration.ofMinutes(10), Duration.ofDays(7),
                Duration.ofMinutes(5), privateKeyPath, jwksDir.toString(), kid);
    }

    @Test
    void signsWithTheActiveKid() {
        var provider = new FileSystemSigningKeyProvider(properties(privateKey.toString(), "2026-09"));

        assertThat(provider.signingKey().getKeyID()).isEqualTo("2026-09");
        assertThat(provider.signingKey().isPrivate()).isTrue();
    }

    @Test
    void jwksPublishesEveryPublicKeyInTheDirectory() {
        var provider = new FileSystemSigningKeyProvider(properties(privateKey.toString(), "2026-09"));

        assertThat(provider.publicJwks().getKeys()).extracting(key -> key.getKeyID())
                .containsExactlyInAnyOrder("2026-09", "2026-03");
    }

    @Test
    void jwksDoesNotExposePrivateKeys() {
        var provider = new FileSystemSigningKeyProvider(properties(privateKey.toString(), "2026-09"));

        assertThat(provider.publicJwks().getKeys()).allMatch(key -> !key.isPrivate());
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
