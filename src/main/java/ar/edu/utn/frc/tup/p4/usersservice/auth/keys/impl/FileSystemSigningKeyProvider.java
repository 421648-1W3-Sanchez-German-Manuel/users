package ar.edu.utn.frc.tup.p4.usersservice.auth.keys.impl;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.stream.Stream;

/** Loads mounted PEM files and deliberately has no in-memory key fallback. */
@Component
public class FileSystemSigningKeyProvider implements SigningKeyProvider {

    private final RSAKey signingKey;
    private final JWKSet publicJwks;

    public FileSystemSigningKeyProvider(JwtProperties properties) {
        Path privateKeyPath = Path.of(properties.privateKeyPath());
        if (!Files.isReadable(privateKeyPath)) {
            throw new IllegalStateException(
                    "Cannot read private key at " + privateKeyPath
                            + ". DEC-18 requires keys to be mounted as secrets; no in-memory fallback exists.");
        }

        List<JWK> publicKeys = readPublicKeys(Path.of(properties.publicKeysDir()));
        RSAKey activePublicKey = publicKeys.stream()
                .map(RSAKey.class::cast)
                .filter(key -> key.getKeyID().equals(properties.activeKid()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Active kid '" + properties.activeKid() + "' has no public key in "
                                + properties.publicKeysDir()));

        try {
            RSAPrivateKey privateKey = (RSAPrivateKey) JWK
                    .parseFromPEMEncodedObjects(Files.readString(privateKeyPath))
                    .toRSAKey()
                    .toPrivateKey();
            this.signingKey = new RSAKey.Builder(activePublicKey)
                    .privateKey(privateKey)
                    .keyID(properties.activeKid())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse private key at " + privateKeyPath, exception);
        }

        this.publicJwks = new JWKSet(publicKeys);
    }

    private List<JWK> readPublicKeys(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(path -> path.toString().endsWith(".pem"))
                    .sorted()
                    .map(this::readPublicKey)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read public key directory " + directory, exception);
        }
    }

    private JWK readPublicKey(Path path) {
        try {
            String filename = path.getFileName().toString();
            String kid = filename.substring(0, filename.length() - ".pem".length());
            RSAPublicKey publicKey = (RSAPublicKey) JWK
                    .parseFromPEMEncodedObjects(Files.readString(path))
                    .toRSAKey()
                    .toPublicKey();
            return new RSAKey.Builder(publicKey)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot parse public key at " + path, exception);
        }
    }

    @Override
    public RSAKey claveDeFirma() {
        return signingKey;
    }

    @Override
    public JWKSet jwksPublico() {
        return publicJwks.toPublicJWKSet();
    }
}
