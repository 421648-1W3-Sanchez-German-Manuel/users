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
     * Las claves se generan con la JDK, NO invocando `openssl`.
     *
     * Con ProcessBuilder esto fallaba con "CreateProcess error=2" en cualquier
     * Windows sin openssl en el PATH — y openssl no esta en el PATH de Windows
     * por defecto, viene con Git pero en su propio bin. Un test unitario que
     * depende de un binario externo no prueba el codigo, prueba la maquina.
     *
     * El formato es el mismo que escribe openssl y el mismo que espera
     * `JWK.parseFromPEMEncodedObjects`: getEncoded() de una clave privada da
     * PKCS#8 y de una publica da SPKI, que son exactamente los dos PEM
     * estandar.
     */
    @BeforeAll
    static void generateKeys() throws Exception {
        privateKey = dir.resolve("jwt-private.pem");
        jwksDir = Files.createDirectories(dir.resolve("jwks"));

        KeyPair active = rsa();
        writePem(privateKey, "PRIVATE KEY", active.getPrivate().getEncoded());
        writePem(jwksDir.resolve("2026-09.pem"), "PUBLIC KEY", active.getPublic().getEncoded());

        // Una segunda clave, solo publica: es la que ya no firma pero sigue
        // publicada en el JWKS para que los tokens en vuelo sigan validando
        // durante una rotacion (DEC-18).
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
