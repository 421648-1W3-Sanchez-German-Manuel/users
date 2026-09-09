package ar.edu.utn.frc.tup.p4.usersservice.auth.services;

import ar.edu.utn.frc.tup.p4.usersservice.auth.keys.SigningKeyProvider;
import ar.edu.utn.frc.tup.p4.usersservice.auth.tokens.TokenClaims;
import ar.edu.utn.frc.tup.p4.usersservice.config.JwtProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Signs compact RS256 tokens with the active mounted key. */
@Service
public class TokenService {

    private final SigningKeyProvider keys;
    private final JwtProperties properties;

    public TokenService(SigningKeyProvider keys, JwtProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    public String firmarPersona(TokenClaims claims) {
        return firmar(claims, properties.accessTtl());
    }

    public String firmarServicio(TokenClaims claims) {
        return firmar(claims, properties.serviceTtl());
    }

    private String firmar(TokenClaims claims, Duration lifetime) {
        try {
            var signingKey = keys.claveDeFirma();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(signingKey.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(
                    header,
                    claims.aClaimsSet(properties.issuer(), lifetime));
            jwt.sign(new RSASSASigner(signingKey.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign JWT", exception);
        }
    }
}
